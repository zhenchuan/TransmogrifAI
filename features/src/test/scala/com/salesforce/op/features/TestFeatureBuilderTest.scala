/*
 * Copyright (c) 2017, Salesforce.com, Inc.
 * All rights reserved.
 */

package com.salesforce.op.features


import com.salesforce.op.features.types._
import com.salesforce.op.test.{TestFeatureBuilder, TestSparkContext}
import com.salesforce.op.utils.spark.RichRow._
import org.apache.spark.sql.DataFrame
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, Matchers}


case class FeatureBuilderContainerTest(s: String, l: Long, d: Double)


@RunWith(classOf[JUnitRunner])
class TestFeatureBuilderTest extends FlatSpec with TestSparkContext {

  Spec(TestFeatureBuilder.getClass) should "infer features from a dataset" in {
    // scalastyle:off
    import spark.implicits._
    // scalastyle:on
    val ds = Seq(
      FeatureBuilderContainerTest("blah1", 10, 2.0),
      FeatureBuilderContainerTest("blah2", 11, 3.0)
    ).toDS.toDF()

    val features@Array(f1, f2, f3) = TestFeatureBuilder(ds, Set.empty[String])

    f1.name shouldBe "s"
    f1.typeName shouldBe FeatureType.typeName[Text]
    f2.name shouldBe "l"
    f2.typeName shouldBe FeatureType.typeName[Integral]
    f3.name shouldBe "d"
    f3.typeName shouldBe FeatureType.typeName[Real]
  }

  it should "create a dataset with one feature" in {
    val res@(ds, f1) = TestFeatureBuilder[Real](Seq(Real(1), Real(2L), Real(3.1f), Real(4.5)))

    f1.name shouldBe "f1"
    f1.typeName shouldBe FeatureType.typeName[Real]

    assertResults(ds, res, expected = Seq(1, 2L, 3.1f, 4.5))
  }

  it should "create a dataset with two features" in {
    val res@(ds, f1, f2) = TestFeatureBuilder(
      Seq[(Text, Integral)](
        (Text("one"), Integral(1)), (Text("two"), Integral(2)), (Text("NULL"), Integral.empty)
      )
    )
    f1.name shouldBe "f1"
    f1.typeName shouldBe FeatureType.typeName[Text]
    f2.name shouldBe "f2"
    f2.typeName shouldBe FeatureType.typeName[Integral]

    assertResults(ds, res, expected = Seq(("one", 1), ("two", 2), ("NULL", null)))
  }

  case class TFBClassTest(s: Text, x: Integral)

  it should "create a dataset with two features with a case class" in {
    val res@(ds, f1, f2) = TestFeatureBuilder[Text, Integral](
      Seq(
        TFBClassTest(Text("one"), Integral(1)),
        TFBClassTest(Text("two"), Integral(2)),
        TFBClassTest(Text("NULL"), Integral.empty)
      ).flatMap(TFBClassTest.unapply)
    )
    assertResults(ds, res, expected = Seq(("one", 1), ("two", 2), ("NULL", null)))
  }

  it should "create a dataset with three features" in {
    val res@(ds, f1, f2, f3) = TestFeatureBuilder(Seq[(Text, Integral, Real)](
      (Text("one"), Integral(1), Real(1.0)),
      (Text("two"), Integral(2), Real(2.3)),
      (Text("NULL"), Integral.empty, Real.empty)
    ))
    f1.name shouldBe "f1"
    f1.typeName shouldBe FeatureType.typeName[Text]
    f2.name shouldBe "f2"
    f2.typeName shouldBe FeatureType.typeName[Integral]
    f3.name shouldBe "f3"
    f3.typeName shouldBe FeatureType.typeName[Real]

    assertResults(ds, res, expected = Seq(("one", 1, 1.0), ("two", 2, 2.3), ("NULL", null, null)))
  }

  it should "create a dataset with four features" in {
    val res@(ds, f1, f2, f3, f4) = TestFeatureBuilder(
      Seq[(Text, Integral, Real, Integral)](
        (Text("one"), Integral(1), Real(1.0), Integral(-1)),
        (Text("two"), Integral(2), Real(2.3), Integral(1)),
        (Text("NULL"), Integral.empty, Real.empty, Integral(1))
      )
    )
    f1.name shouldBe "f1"
    f1.typeName shouldBe FeatureType.typeName[Text]
    f2.name shouldBe "f2"
    f2.typeName shouldBe FeatureType.typeName[Integral]
    f3.name shouldBe "f3"
    f3.typeName shouldBe FeatureType.typeName[Real]
    f4.name shouldBe "f4"
    f4.typeName shouldBe FeatureType.typeName[Integral]

    assertResults(ds, res, expected = Seq(("one", 1, 1.0, -1), ("two", 2, 2.3, 1), ("NULL", null, null, 1)))
  }

  it should "create a dataset with five features" in {
    val res@(ds, f1, f2, f3, f4, f5) = TestFeatureBuilder(
      Seq[(Text, Integral, Real, Integral, MultiPickList)](
        (Text("one"), Integral(1), Real(1.0), Integral(-1), new MultiPickList(Set("1", "2", "2"))),
        (Text("two"), Integral(2), Real(2.3), Integral(1), new MultiPickList(Set("3", "4")))
      )
    )
    f1.name shouldBe "f1"
    f1.typeName shouldBe FeatureType.typeName[Text]
    f2.name shouldBe "f2"
    f2.typeName shouldBe FeatureType.typeName[Integral]
    f3.name shouldBe "f3"
    f3.typeName shouldBe FeatureType.typeName[Real]
    f4.name shouldBe "f4"
    f4.typeName shouldBe FeatureType.typeName[Integral]
    f5.name shouldBe "f5"
    f5.typeName shouldBe FeatureType.typeName[MultiPickList]

    assertResults(ds, res, expected = Seq(("one", 1, 1.0, -1, List("1", "2")), ("two", 2, 2.3, 1, List("3", "4"))))
  }

  private def assertResults(ds: DataFrame, res: Product, expected: Traversable[Any]): Unit = {
    val features = res.productIterator.collect { case f: FeatureLike[_] => f }.toArray

    ds.schema.fields.map(f => f.name -> f.dataType) should contain theSameElementsInOrderAs
      features.map(f => f.name -> FeatureSparkTypes.sparkTypeOf(f.wtt))

    ds.collect().map(row => features.map(f => row.getAny(f.name))) should contain theSameElementsInOrderAs
      expected.map{ case v: Product => v; case v => Tuple1(v) }.map(_.productIterator.toArray)
  }


}