package com.sidooo.sewing;

import java.io.IOException;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.CounterGroup;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.stereotype.Service;

import com.sidooo.crawl.FetchContent;
import com.sidooo.senode.MongoConfiguration;

@Service("merger")
public class Merger extends SewingConfigured implements Tool {

	public static class MergeMapper extends
			Mapper<Text, FetchContent, Text, FetchContent> {

		@Override
		public void map(Text key, FetchContent value, Context context)
				throws IOException, InterruptedException {
			context.write(key, value);
			context.getCounter("Sewing", "INPUT").increment(1);
		}
	}

	public static class MergeReducer extends
			Reducer<Text, FetchContent, Text, FetchContent> {

		@Override
		public void reduce(Text key, Iterable<FetchContent> values,
				Context context) throws IOException, InterruptedException {
			for (FetchContent value : values) {
				context.write(key, value);
				context.getCounter("Sewing", "OUTPUT").increment(1);
			}
		}

	}

	@Override
	public int run(String[] args) throws Exception {
		
		String date = args[0];
		LOG.info("Merge Target : " + date);
		
		Job job = new Job(getConf());

		job.setJobName("Sewing Merge");
		job.setJarByClass(Merger.class);
		
		Path[] crawlFiles = TaskData.submitCrawlInput(job, date);
		if (crawlFiles.length <= 0) {
			LOG.info("Crawl Input File Count : 0");
			return 1;
		}

		TaskData.submitCrawlOutput(job, date);

		job.setMapperClass(MergeMapper.class);
		job.setMapOutputKeyClass(Text.class);
		job.setMapOutputValueClass(FetchContent.class);
		job.setReducerClass(MergeReducer.class);
		job.setOutputKeyClass(Text.class);
		job.setOutputValueClass(FetchContent.class);
		job.setNumReduceTasks(1);

		boolean success = job.waitForCompletion(true);
		if (success) {
			CounterGroup group = job.getCounters().getGroup("Sewing");
			long successCount = group.findCounter("INPUT").getValue();
			System.out.println("INPUT Count: " + successCount);
			long failCount = group.findCounter("OUTPUT").getValue();
			System.out.println("OUTPUT Count: " + failCount);
			
			for(Path crawlFile : crawlFiles) {
				TaskData.deleteCrawlFile(job, crawlFile);
				LOG.info("Delete Crawl File: " + crawlFile.toString());
			}
			
			return 0;
		} else {
			return 1;
		}
	}

	public static void main(String[] args) throws Exception {
		
		if (args.length != 1) {
			System.out.println("Usage: <Program> <20150216>");
			return;
		}
		
		if (args[0].length() != 8) { 
			System.out.println("Invalid Date Format.");
			return;
		}
		
		@SuppressWarnings("resource")
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
				MongoConfiguration.class);
		context.scan("com.sidooo.seed", "com.sidooo.sewing");
		Merger merger = context.getBean("merger", Merger.class);

		// conf.set("hbase.zookeeper.quorum",
		// "node4.sidooo.com,node8.sidooo.com,node13.sidooo.com");
		int res = ToolRunner.run(SewingConfiguration.create(), merger, args);
		System.exit(res);
	}

}