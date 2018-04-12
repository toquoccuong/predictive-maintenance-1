package com.mapr.examples

import com.mapr.db.spark.{field, _}
import com.mapr.db.spark.sql._
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.spark.SparkConf
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{SparkSession, _}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._
import org.apache.spark.streaming.kafka09.{ConsumerStrategies, KafkaUtils, LocationStrategies}
import org.apache.spark.streaming.{Seconds, StreamingContext}
import breeze.linalg.{DenseVector, norm}
import breeze.signal._


/******************************************************************************
  PURPOSE:

  Calculate Fourier transforms for streaming time-series data. This is intended to demonstrate how to detect anomalies in data from vibration sensors.

  AUTHOR:
  BUILD:

  `mvn package`
  copy target/lib to your cluster

  SYNTHESIZE DATA:

  java -cp target/factory-iot-tutorial-1.0-jar-with-dependencies.j com.mapr.examples.HighSpeedProducer /apps/mqtt:vibration 10

  RUN:

  /opt/mapr/spark/spark-2.1.0/bin/spark-submit --class com.mapr.examples.StreamingFourierTransform target/factory-iot-tutorial-1.0-jar-with-dependencies.jar <stream:topic> [vibration_change_threshold]

  EXAMPLE:

  /opt/mapr/spark/spark-2.1.0/bin/spark-submit --class com.mapr.examples.StreamingFourierTransform target/factory-iot-tutorial-1.0-jar-with-dependencies.jar /apps/mqtt:vibration 25.0

  ****************************************************************************/

object StreamingFourierTransform {

  case class Signal(t: Double, amplitude: Double) extends Serializable

  def main(args: Array[String]): Unit = {
    if (args.length < 1) {
      System.err.println("USAGE: StreamingFourierTransform <stream:topic> [deviation_threshold]")
      System.err.println("EXAMPLE: spark-submit --class com.mapr.examples.StreamingFourierTransform target/factory-iot-tutorial-1.0-jar-with-dependencies.jar /apps/mqtt:vibration 25.0")
      System.exit(1)
    }
    println("Waiting for messages on stream " + args(0) + "...")
    var deviation_tolerance = 8.0
    if (args.length > 1) {
      deviation_tolerance = args(1).toDouble
    }
    println("Alerting when FFT signal changes more than " + deviation_tolerance + "%")
    val schema = StructType(Array(
      StructField("t", DoubleType, nullable = true),
      StructField("amplitude", DoubleType, nullable = true)
    ))
    val groupId = "testgroup"
    val offsetReset = "earliest"  //  "latest"
    val pollTimeout = "5000"
    val brokers = "this.will.be.ignored:9092" // not needed for MapR Streams, needed for Kafka
    val sparkConf = new SparkConf()
      .setAppName(StreamingFourierTransform.getClass.getName).setMaster("local[*]")
    val ssc = new StreamingContext(sparkConf, Seconds(2))
    // Use this if you're working in spark-shell:
    // var ssc = new StreamingContext(sc,Seconds(5))
    val sc = ssc.sparkContext
    ssc.sparkContext.setLogLevel("ERROR")
    val topicsSet = args(0).split(",").toSet
    val kafkaParams = Map[String, String](
      ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG -> brokers,
      ConsumerConfig.GROUP_ID_CONFIG -> groupId,
      ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG ->
        "org.apache.kafka.common.serialization.StringDeserializer",
      ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG ->
        "org.apache.kafka.common.serialization.StringDeserializer",
      ConsumerConfig.AUTO_OFFSET_RESET_CONFIG -> offsetReset,
      ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG -> "false",
      "spark.kafka.poll.time" -> pollTimeout
    )
    val consumerStrategy = ConsumerStrategies.Subscribe[String, String](topicsSet, kafkaParams)
    val messagesDStream = KafkaUtils.createDirectStream[String, String](
      ssc, LocationStrategies.PreferConsistent, consumerStrategy
    )
    val valuesDStream = messagesDStream.map(_.value())
    // Initialize FFT comparison
    var b = breeze.linalg.DenseVector[Double]()
    valuesDStream.foreachRDD { (rdd: RDD[String]) =>
      if (!rdd.isEmpty) {
        val spark = SparkSession.builder.config(rdd.sparkContext.getConf).getOrCreate()
        import spark.implicits._
        val ds: Dataset[Signal] = spark.read.schema(schema).json(rdd).as[Signal]
        println("Number of samples received: " + ds.count())
        val required_sample_size = 600
        // Consequtive RDDs may be out of phase, so just take the last 600 and sort them
        // so we're always taking FFTs on consistent representations of the time-domain signal
        val amplitude_series = ds
          .limit(required_sample_size)
          .sort(asc("t"))
          .select("amplitude")
          .collect.map(_.getDouble(0)).take(required_sample_size)
        // Skip FFT calculation if we have insufficient samples
        if (amplitude_series.length < required_sample_size) {
          println("Insufficient samples to determine frequency profile")
        } else {
          // Convert amplitude series to frequency domain using Fourier Transform
          val fft = fourierTr(DenseVector(amplitude_series: _*))
          // Calculate the similarity between this fft and the last one we calculated.
          // Drop the phase from the FFT so we're just dealing with real numbers.
          val a = fft.map(x => x.real)
          // Skip similarity calculation for the first RDD, and when RDDs vary drastically in size.
          if (b.length > 0 && (a.length/b.length < 2)) {
            // Cosine similarity formula: (a dot b) / math.sqrt((a dot a) * (b dot b))
            val cosine_similarity = math.abs((a dot b) / math.sqrt((a dot a) * (b dot b)))
            val fft_change = 100d-cosine_similarity*100d
            //            println(f"FFT similarity: $cosine_similarity%2.2f")
            println(f"Vibration signal has changed by $fft_change%2.2f%%")
            if (fft_change > deviation_tolerance)
              println("<---------- SIMULATING FAILURE EVENT ---------->")
          }
          // Save this FFT so we can measure rate of change for the next RDD.
          b = a
        }
      }
    }
    ssc.start()
    ssc.awaitTermination()
    ssc.stop(stopSparkContext = true, stopGracefully = true)
  }

}

