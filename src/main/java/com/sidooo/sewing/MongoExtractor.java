package com.sidooo.sewing;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.CounterGroup;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.stereotype.Service;

import com.sidooo.ai.Keyword;
import com.sidooo.ai.Recognition;
import com.sidooo.content.HttpContent;
import com.sidooo.counter.CountService;
import com.sidooo.extractor.ContentExtractor;
import com.sidooo.extractor.ExtractorManager;
import com.sidooo.point.Item;
import com.sidooo.point.Link;
import com.sidooo.point.LinkRepository;
import com.sidooo.point.Point;
import com.sidooo.point.PointRepository;
import com.sidooo.point.PointService;
import com.sidooo.seed.Seed;
import com.sidooo.seed.SeedService;
import com.sidooo.senode.MongoConfiguration;
import com.sidooo.senode.RedisConfiguration;

@Service("mongoExtractor")
public class MongoExtractor extends Configured implements Tool {

	public static final Logger LOG = LoggerFactory.getLogger("MongoExtractor");

	@Autowired
	private SeedService seedService;

	@Autowired
	private CountService countService;
	
	@Autowired
	private PointService pointService;

	public static class ExtractMapper extends
			Mapper<Text, HttpContent, Keyword, Text> {

		private Recognition recognition;

		private List<Seed> seeds;

		private PointRepository pointRepo;

		private CountService countService;
		
		private ExtractorManager manager;

		@Override
		public void setup(Context context) throws IOException,
				InterruptedException {

			Configuration conf = context.getConfiguration();
			seeds = CacheLoader.loadSeedList(conf);
			if (seeds == null) {
				throw new InterruptedException("Seed List is null.");
			}
			if (!CacheLoader.exiistHanlpData(conf)) {
				throw new InterruptedException("Hanlp Data Archive not found.");
			}
			if (!CacheLoader.existHanlpJar(conf)) {
				throw new InterruptedException("Hanlp Jar not found.");
			}
			recognition = new Recognition();

			@SuppressWarnings("resource")
			AnnotationConfigApplicationContext appcontext = new AnnotationConfigApplicationContext(
					MongoConfiguration.class, RedisConfiguration.class);
			appcontext.scan("com.sidooo.point", "com.sidooo.counter");
			pointRepo = appcontext.getBean("pointRepository",
					PointRepository.class);

			countService = appcontext.getBean("countService",
					CountService.class);
			
			manager = new ExtractorManager();


		}

		@Override
		public void cleanup(Context context) throws IOException,
				InterruptedException {

		}

		@Override
		public void map(Text key, HttpContent fetch, Context context)
				throws IOException, InterruptedException {

			String url = key.toString();

			Seed seed = SeedService.getSeedByUrl(url, seeds);
			if (seed == null) {
				return;
			}

			byte[] content = fetch.getContent();
			if (content == null || content.length < 8) {
				return;
			}

			ContentExtractor extractor = null;
			String mime = fetch.getContentType();

			// 根据爬虫的应答头部识别文件格式
			if (mime != null && mime.length() > 0) {
				extractor = manager.getInstanceByMime(mime);
			}

			// 根据后缀名识别出文件格式
			if (extractor == null) {
				extractor = manager.getInstanceByUrl(url);
			}

			// 根据内容识别文件格式
			if (extractor == null) {
				extractor = manager.getInstanceByContent(content);
			}

			if (extractor == null) {
//				LOG.warn("Unknown File Format, Url: " + url + ", Content Size:"
//						+ content.length);
				context.getCounter("Sewing", "ERR_NOEXTRACTOR").increment(1);
				return;
			}

			extractor.setUrl(url);
			ByteArrayInputStream input = new ByteArrayInputStream(content);
			try {
				extractor.setInput(input, null);
			} catch (Exception e) {
//				LOG.error("Extract " + url + " Fail.", e);
				context.getCounter("Sewing", "ERR_INPUT").increment(1);
				return;
			}
			
			String item = null;
			Point point = new Point();
			while((item = extractor.extract()) != null) {
				if (item.length() <= 0) {
					continue;
				}

				Keyword[] keywords = null;
				try {
					keywords = recognition.search(item);
				} catch (Exception e) {
//					LOG.error("Item:" + item.getId() +" Recognite Fail.", e);
					context.getCounter("Sewing", "ERR_RECOGNITE").increment(1);
					continue;
				}

				if (keywords == null || keywords.length <= 0) {
//					LOG.warn("Item:" + item.getId() +" Keyword not found.");
					context.getCounter("Sewing", "ERR_NOKEYWORD").increment(1);
					continue;
				}

				point.clear();
				point.setDocId(Point.md5(item));
				point.setTitle(seed.getName());
				point.setUrl(url);
				for (Keyword keyword : keywords) {
					point.addLink(keyword);
					context.write(keyword, new Text(point.getDocId()));
					countService.incLinkCount(seed.getId());
				}

				pointRepo.createPoint(point);
				context.getCounter("Sewing", "Point").increment(1);
				//points.add(point);
				//LOG.info(point.toString());

			}
			extractor.close();
		
			
			//countService.incPointCount(seed.getId(), points.size());
			
			//LOG.info(url + ", Extractor:" + extractor.getClass().getName() + ", Item Count:"
			//		+ items.size());
			
		}
	}

	public static class ExtractReducer extends
			Reducer<Keyword, Text, NullWritable, NullWritable> {

		private LinkRepository linkRepo;

		@Override
		public void setup(Context context) throws IOException,
				InterruptedException {

			@SuppressWarnings("resource")
			AnnotationConfigApplicationContext appcontext = new AnnotationConfigApplicationContext(
					MongoConfiguration.class, RedisConfiguration.class);
			appcontext.scan("com.sidooo.point", "com.sidooo.counter");
			linkRepo = appcontext.getBean("linkRepository",
					LinkRepository.class);

		}

		@Override
		public void cleanup(Context context) throws IOException,
				InterruptedException {

		}

		@Override
		protected void reduce(Keyword keyword, Iterable<Text> values,
				Context context) throws IOException, InterruptedException {

			Link link = new Link();
			link.setKeyword(keyword.getWord());
			link.setType(keyword.getAttr());

			Iterator<Text> points = values.iterator();
			if (points.hasNext()) {
				Text point = points.next();
				link.addPoint(point.toString());
			}

			linkRepo.createLink(link);

			LOG.info(link.toString());
			context.getCounter("Sewing", "Link").increment(1);
		}
	}

	@Override
	public int run(String[] arg0) throws Exception {

		Configuration conf = getConf();
		conf.setInt("mapreduce.map.cpu.vcores", 4);
		LOG.info("mapreduce.map.cpu.vcores : " + getConf().getInt("mapreduce.map.cpu.vcores", 1));
		
		
		Job job = new Job(conf);
		job.setJobName("Sewing Extract");
		job.setJarByClass(MongoExtractor.class);

		// 设置缓存
		List<Seed> seeds = seedService.getEnabledSeeds();
		CacheSaver.submitSeedCache(job, seeds);
		CacheSaver.submitNlpCache(job);

		// 设置输入
		TaskData.submitCrawlInput(job);
		//TaskData.SubmitTestCrawlInput(job);

		// 设置输出
		// TaskData.submitPointOutput(job);

		// 设置计算流程
		job.setMapperClass(ExtractMapper.class);
		job.setMapOutputKeyClass(Keyword.class);
		job.setMapOutputValueClass(Text.class);

		job.setReducerClass(ExtractReducer.class);
		TaskData.submitNullOutput(job);
		job.setNumReduceTasks(10);

		for (Seed seed : seeds) {
			countService.resetCount(seed.getId());
		}
		
		pointService.clearPoints();
		pointService.clearLinks();

		boolean success = job.waitForCompletion(true);
		if (success) {
			CounterGroup group = job.getCounters().getGroup("Sewing");
			long pointCount = group.findCounter("Point").getValue();
			System.out.println("Point Count: " + pointCount);
			long linkCount = group.findCounter("Link").getValue();
			System.out.println("Link Count: " + linkCount);
			long errCount = group.findCounter("ERR_NOEXTRACTOR").getValue();
			System.out.println("ERR_NOEXTRACTOR Count: " + errCount);
			errCount = group.findCounter("ERR_RECOGNITE").getValue();
			System.out.println("ERR_RECOGNITE Count: " + errCount);
			errCount = group.findCounter("ERR_EXTRACT").getValue();
			System.out.println("ERR_EXTRACT Count: " + errCount);
			errCount = group.findCounter("ERR_NOKEYWORD").getValue();
			System.out.println("ERR_NOKEYWORD Count: " + errCount);

			for (Seed seed : seeds) {
				long seedPointCount = countService.getPointCount(seed.getId());
				long seedLinkCount = countService.getLinkCount(seed.getId());

				seedService.incAnalysisStatistics(seed.getId(),
						seedPointCount, seedLinkCount);
			}
			return 0;
		} else {
			return 1;
		}

	}

	public static void main(String args[]) throws Exception {

		@SuppressWarnings("resource")
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
				MongoConfiguration.class, RedisConfiguration.class);
		context.scan("com.sidooo.seed", "com.sidooo.sewing");
		MongoExtractor extractor = context.getBean("mongoExtractor",
				MongoExtractor.class);

		int res = ToolRunner.run(SewingConfiguration.create(), extractor, args);
		System.exit(res);
	}
}