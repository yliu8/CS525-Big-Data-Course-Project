package org;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Vector;

import org.apache.hadoop.conf.*;
import org.apache.hadoop.filecache.DistributedCache;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.*;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.util.*;

public class KmeansCluster extends Configured implements Tool
{
	// iteration Control, Maximun 6 iterations here
	private static final int MAXITERATIONS = 0;
	// threshold to determine whether a new centroid is changed from previous one
	private static final double THRESHOLD = 2;


	public static boolean stopIteration(Configuration conf) throws IOException 
	{
		FileSystem fs = FileSystem.get(conf);
		Path pervCenterFile = new Path("/hzhou/centroids");
		Path currentCenterFile = new Path("/hzhou/newCentroid/part-r-00000");
		if(!(fs.exists(pervCenterFile) && fs.exists(currentCenterFile)))
		{
			System.exit(1);
		}
		
		// delete pervCenterFile, then rename the currentCenterFile to pervCenterFile
		/* Why rename needed here: 
		 * as each iteration, the mapper will get centroid from Path("/hzhou/input/initK")
		 * but reducer will output new centroids into Path("/hzhou/output/newCentroid/part-r-00000")
		 */ 
		fs.delete(pervCenterFile,true);
		if(fs.rename(currentCenterFile, pervCenterFile) == false)
		{
			System.exit(1);
		}
		
		//check whether the centers have changed or not to determine to do iteration or not
		boolean stop=true;
		String line;
		FSDataInputStream in = fs.open(pervCenterFile);
		InputStreamReader isr = new InputStreamReader(in);
		BufferedReader br = new BufferedReader(isr);

		/* get each line, check the third parameter, if it is 0, it means this centoid is changed from last iteration, 
		 * so, we need more iteration
		 */
		while((line = br.readLine()) != null)
		{
			String []str1 = line.split(",");
			int isntChange = Integer.parseInt(str1[2].trim());
			if(isntChange < 1)
			{
				stop = false;
				break;
			}
		}
		
		return stop;
	}
	 
	
	public static class ClusterMapper extends Mapper<LongWritable, Text, Text, Text>  //output<centroid,point>
	{
		Vector<String> centers = new Vector<String>();
		int k = 0;

		// load centroids from Path("/hzhou/input/initK") in setup() function
		@Override
		public void setup(Context context)
		{
			try
			{
				Path[] caches=DistributedCache.getLocalCacheFiles(context.getConfiguration());
				if(caches == null || caches.length <= 0)
				{
					System.exit(1);
				}
				
				BufferedReader br = new BufferedReader(new FileReader(caches[0].toString()));
				String line;
				while((line=br.readLine()) != null)
				{
					centers.add(line);
					k++;		   
				}
				br.close();
			}
			catch(Exception e){}
		}

		@Override
		//output<centroid,point>
		public void map(LongWritable key, Text value, Context context) throws IOException, InterruptedException 
		{
			
			int index = -1;
			double minDist = Double.MAX_VALUE;
			String tweetVector = value.toString().trim();
			//String tweetIndex = Point.getTweetIndex(tweetVector);
			

			// find the nearest centroid to this point
			for(int i = 0;i < k; i++)
			{
				double dist = Point.getManhtDist(tweetVector, centers.get(i));
				if(dist < minDist)
				{
					minDist = dist;
					index = i;
				}
			}
			// output the nearest centroid as key, and the piont as value
			context.write(new Text(centers.get(index)), new Text(tweetVector));
		}
		
		@Override
		public void cleanup(Context context) throws IOException,InterruptedException 
		{
			// do nothing here
		}
	}
	
	// do aggregation in local side
	// add one more parameter in value field
	public static class Combiner extends Reducer<Text, Text, Text, Text> 
	{	  
		@Override
		public void reduce(Text key,Iterable<Text> values,Context context) throws IOException,InterruptedException
		{
			String outputValue;
			String sumStr = "";
			int count=0;
			while(values.iterator().hasNext())
			{
				String line = values.iterator().next().toString();
				sumStr = Point.getSum(sumStr, line);
				count++;
			}
			outputValue = sumStr + "-" + String.valueOf(count);  //value=Point_Sum+count
			context.write(key, new Text(outputValue));
		}
	}
 
	public static class UpdateCenterReducer extends Reducer<Text, Text, Text, Text> 
	{
		@Override
		public void setup(Context context)
		{
			// do nothing here
		}
 
		@Override
		public void reduce(Text key,Iterable<Text> values,Context context) throws IOException,InterruptedException
		{
			int count = 0;
			String sumStr = "";
			String newCentroid = "";

			// while loop to calculate the sum of points
			while(values.iterator().hasNext())
			{
				String line = values.iterator().next().toString();
				String[] str = line.split(":");
				count += Integer.parseInt(str[1]);
				sumStr = Point.getSum(sumStr, str[0]);
			}

			// calculate the new centroid
			newCentroid = Point.getNewCentroid(sumStr, count);
			
			// get prevois centroid
			String preCentroid = key.toString();
			

			// compare the new & previous centroids, 
			/*
			 * If it is not "changed", make the value of the output be 1
			 * otherwise,  make the value of the output be 0
			 * the value filed will be use in stopIteration() function, which will be called in main() after each iteration
			 */
			if(Point.getManhtDist(preCentroid, newCentroid) <= THRESHOLD) 
			{
				context.write(new Text(newCentroid),new Text(",1"));
			}
			else
			{
				context.write(new Text(newCentroid),new Text(",0"));
			}
		}
		
		@Override
		public void cleanup(Context context) throws IOException,InterruptedException 
		{
			// do nothing here
		}
	}
	@Override
	public int run(String[] args) throws Exception 
	{
		Configuration conf = getConf();
		FileSystem fs = FileSystem.get(conf);
		Job job = new Job(conf);
		job.setJarByClass(KmeansCluster.class);
		
		FileInputFormat.setInputPaths(job, "/hzhou/tfidf-vectors");
		Path outDir = new Path("/hzhou/newCentroid");
		fs.delete(outDir,true);
		FileOutputFormat.setOutputPath(job, outDir);
		 
		job.setInputFormatClass(TextInputFormat.class);
		job.setOutputFormatClass(TextOutputFormat.class);
		job.setMapperClass(ClusterMapper.class);
		//job.setCombinerClass(Combiner.class);
		//job.setReducerClass(UpdateCenterReducer.class);
		//job.setNumReduceTasks(1);// so that all new centroids will output into one file
		job.setOutputKeyClass(Text.class);
		job.setOutputValueClass(Text.class);
		 
		return job.waitForCompletion(true)?0:1;
	}
	
	
	public static void main(String[] args) throws Exception 
	{
		Configuration conf = new Configuration();
		FileSystem fs = FileSystem.get(conf);
		
		// set the path for cache, which will be loaded in ClusterMapper
		Path dataFile = new Path("/hzhou/centroids");
		DistributedCache.addCacheFile(dataFile.toUri(), conf);
 
		int iteration = 1;
		int success = 1;
		//do 
		//{
			success ^= ToolRunner.run(conf, new KmeansCluster(), args);
			iteration++;
		//} while (success == 1 && (!stopIteration(conf)) && iteration < MAXITERATIONS ); // take care of the order, I make stopIteration() prior to iteration, because I must keep the initK always contain the lastest centroids after each iteration
		 
		// for final output(just a mapper only task <centroid, point>)
		/*
		Job job = new Job(conf);
		job.setJarByClass(KmeansCluster.class);
		
		FileInputFormat.setInputPaths(job, "/hzhou/tfidf-vectors");
		Path outDir = new Path("/hzhou/finalCluster");
		fs.delete(outDir,true);
		FileOutputFormat.setOutputPath(job, outDir);
		 
		job.setInputFormatClass(TextInputFormat.class);
		job.setOutputFormatClass(TextOutputFormat.class);
		job.setMapperClass(ClusterMapper.class);
		job.setNumReduceTasks(0);
		job.setOutputKeyClass(Text.class);
		job.setOutputValueClass(Text.class);
		 
		job.waitForCompletion(true);
		*/
	}
}