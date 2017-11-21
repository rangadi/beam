package org.example;

import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.testing.ValidatesRunner;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.View;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionView;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class SampleCodeTest {

  public static class LenghtCount extends DoFn<String, Integer> {

    private static final long serialVersionUID = 1L;

    PCollectionView<Integer> maxLenght ;

    public LenghtCount(PCollectionView<Integer> maxRange ){
      this.maxLenght=maxRange;
    }

    @ProcessElement
    public void processElement(ProcessContext c){

      int maxRange = c.sideInput(maxLenght);

      if(c.element().length()<= maxRange){
        c.output(c.element().length());
      }
    }
  }

  @Rule public TestPipeline testP = TestPipeline.create();

  @Test
  @Category(ValidatesRunner.class)
  public void positiveTestCase()
  {
    PCollection<String> stringLine = testP.apply("Passing input strings",Create.of("abcdef","xyz","pqrst","ntnd"));

    PCollection<Integer> wRange = testP.apply("Maximum word length",Create.of(5));

    PCollectionView<Integer> wordRangeConstant = wRange.apply(View.<Integer>asSingleton());

    PCollection<Integer> outputCount = stringLine.apply("Count length of words",
                                                        ParDo.of(new LenghtCount(wordRangeConstant)).withSideInputs(wordRangeConstant));

    PAssert.that(outputCount).containsInAnyOrder(4,3,5);

    testP.run();
  }

}
