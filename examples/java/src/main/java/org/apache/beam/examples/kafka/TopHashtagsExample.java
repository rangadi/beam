/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.examples.kafka;

import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.io.kafka.KafkaIO;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.Validation.Required;
import org.apache.beam.sdk.transforms.Count;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.transforms.Top;
import org.apache.beam.sdk.transforms.Values;
import org.apache.beam.sdk.transforms.windowing.IntervalWindow;
import org.apache.beam.sdk.transforms.windowing.SlidingWindows;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.values.KV;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A Dataflow pipline that showcases {@link KafkaIO}. It reads from a Kafka topic
 * containing <a href="https://dev.twitter.com/overview/api/tweets">JSON Tweets</a>, calculates top
 * hashtags in a 10 minute sliding window. The results are written back to kafka.
 *
 * <pre>{@code
 * Usage:
 *   $ java -cp jar_with_dependencies.jar                                           \
 *          org.apache.beam.contrib.kafka.examples.TopHashtagsExample               \
 *          --project=GCP_PROJECT                                                   \
 *          --stagingLocation=GS_STAGING_DIRECTORY                                  \
 *          --runner=BlockingDataflowPipelineRunner                                 \
 *          --bootstrapServers="kafka_server_1:9092"                                \
 *          --topics="sample_tweets_json"                                           \
 *          --outputTopic="top_hashtags"
 * }</pre>
 */
public class TopHashtagsExample {

  private static final Logger LOG = LoggerFactory.getLogger(TopHashtagsExample.class);

  /**
   * Options for the app.
   */
  public static interface Options extends PipelineOptions {
    @Description("Sliding window length in minutes")
    @Default.Integer(10)
    Integer getSlidingWindowLengthMinutes();
    void setSlidingWindowLengthMinutes(Integer value);

    @Description("Trigger window interval in minutes")
    @Default.Integer(1)
    Integer getSlidingWindowIntervalMinutes();
    void setSlidingWindowIntervalMinutes(Integer value);

    @Description("Bootstrap Server(s) for Kafka")
    @Required
    String getBootstrapServers();
    void setBootstrapServers(String servers);

    @Description("One or more comma separated topics to read from")
    @Required
    List<String> getTopics();
    void setTopics(List<String> topics);

    @Description("Number of Top Hashtags to track")
    @Default.Integer(10)
    Integer getNumTopHashtags();
    void setNumTopHashtags(Integer count);

    @Description("Kafka topic name for writing results")
    @Required
    String getOutputTopic();
    void setOutputTopic(String topic);
  }

  public static void main(String args[]) {

    Options options = PipelineOptionsFactory.fromArgs(args).withValidation().as(Options.class);

    Pipeline pipeline = Pipeline.create(options);

    pipeline
      .apply(KafkaIO.read()
          .withBootstrapServers(options.getBootstrapServers())
          .withTopics(options.getTopics())
          .withValueCoder(StringUtf8Coder.of())
          .withTimestampFn(TWEET_TIMESTAMP_OR_NOW)
          .withoutMetadata())
      .apply(Values.<String>create())
      .apply(ParDo.of(new ExtractHashtagsFn()))
      .apply(Window.<String>into(SlidingWindows
          .of(Duration.standardMinutes(options.getSlidingWindowLengthMinutes()))
          .every(Duration.standardMinutes(options.getSlidingWindowIntervalMinutes()))))
      .apply(Count.<String>perElement())
      .apply(Top.of(options.getNumTopHashtags(), new KV.OrderByValue<String, Long>())
                .withoutDefaults())
      .apply(ParDo.of(new OutputFormatter()))
      .apply(ParDo.of(new KafkaWriter(options)));

    pipeline.run();
  }

  // The rest of the file implements DoFns to do the following:
  //    - extract hashtags
  //    - format results in json
  //    - write the results back to Kafka (useful for fetching monitoring the end result).

  private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

  /**
   * Emit hashtags in the tweet (if any).
   */
  private static class ExtractHashtagsFn extends DoFn<String, String> {

    @Override
    public void processElement(ProcessContext ctx) throws Exception {
      for (JsonNode hashtag : JSON_MAPPER.readTree(ctx.element())
                                         .with("entities")
                                         .withArray("hashtags")) {
        ctx.output(hashtag.get("text").asText());
      }
    }
  }

  // extract timestamp from "timestamp_ms" field.
  private static final SerializableFunction<KV<byte[], String>, Instant> TWEET_TIMESTAMP_OR_NOW =
      new SerializableFunction<KV<byte[], String>, Instant>() {
        @Override
        public Instant apply(KV<byte[], String> kv) {
          try {
            long tsMillis = JSON_MAPPER.readTree(kv.getValue()).path("timestamp_ms").asLong();
            return tsMillis == 0 ? Instant.now() : new Instant(tsMillis);
          } catch (Exception e) {
            throw Throwables.propagate(e);
          }
        }
      };

  // return json string containing top hashtags and window information time
  private static class OutputFormatter extends DoFn<List<KV<String, Long>>, String>
      implements DoFn.RequiresWindowAccess {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormat
        .forPattern("yyyy-MM-dd HH:mm:ss")
        .withZoneUTC();
    private static final ObjectWriter JSON_WRITER = new ObjectMapper()
        .writerFor(OutputJson.class);

    static class OutputJson {
      @JsonProperty String windowStart;
      @JsonProperty String windowEnd;
      @JsonProperty String generatedAt;
      @JsonProperty List<HashtagInfo> topHashtags;

      OutputJson(String windowStart, String windowEnd,
                 String generatedAt, List<HashtagInfo> topHashtags) {
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.generatedAt = generatedAt;
        this.topHashtags = topHashtags;
      }
    }

    static class HashtagInfo {
      @JsonProperty final String hashtag;
      @JsonProperty final long count;
      HashtagInfo(String hashtag, long count) {
        this.hashtag = hashtag;
        this.count = count;
      }
    }

    @Override
    public void processElement(ProcessContext ctx) throws Exception {

      List<HashtagInfo> topHashtags = new ArrayList<>(ctx.element().size());

      for (KV<String, Long> tag : ctx.element()) {
        topHashtags.add(new HashtagInfo(tag.getKey(), tag.getValue()));
      }

      IntervalWindow window = (IntervalWindow) ctx.window();

      String json = JSON_WRITER.writeValueAsString(new OutputJson(
          DATE_FORMATTER.print(window.start()),
          DATE_FORMATTER.print(window.end()),
          DATE_FORMATTER.print(Instant.now()),
          topHashtags));

      ctx.output(json);
    }
  }

  private static class KafkaWriter extends DoFn<String, Void> {

    private final String topic;
    private final Map<String, Object> config;
    private static transient KafkaProducer<String, String> producer = null;

    public KafkaWriter(Options options) {
      this.topic = options.getOutputTopic();
      this.config = ImmutableMap.<String, Object>of(
          "bootstrap.servers", options.getBootstrapServers(),
          "key.serializer",    StringSerializer.class.getName(),
          "value.serializer",  StringSerializer.class.getName());
    }

    @Override
    public void startBundle(Context c) throws Exception {
      if (producer == null) { // in Beam, startBundle might be called multiple times.
        producer = new KafkaProducer<String, String>(config);
      }
    }

    @Override
    public void finishBundle(Context c) throws Exception {
      producer.flush();
    }

    @Override
    public void processElement(ProcessContext ctx) throws Exception {
      LOG.trace("Top Hashtags : {}", ctx.element());
      producer.send(new ProducerRecord<String, String>(topic, ctx.element()));
    }
  }
}
