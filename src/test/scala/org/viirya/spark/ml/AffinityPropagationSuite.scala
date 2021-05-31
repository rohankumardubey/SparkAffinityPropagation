
package org.viirya.spark.ml

import scala.collection.mutable

import org.scalatest.{BeforeAndAfterAll, Suite}
import org.scalatest.funsuite.AnyFunSuite

import org.viirya.spark.ml.AffinityPropagation._

import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.graphx.{Edge, Graph}

class AffinityPropagationSuite extends AnyFunSuite with BeforeAndAfterAll { self: Suite =>
  @transient var sc: SparkContext = _

  override def beforeAll() {
    super.beforeAll()
    val conf = new SparkConf()
      .setMaster("local[2]")
      .setAppName("AffinityPropagationUnitTest")
    sc = new SparkContext(conf)
  }

  override def afterAll() {
    try {
      if (sc != null) {
        sc.stop()
      }
      sc = null
    } finally {
      super.afterAll()
    }
  }  

  test("affinity propagation") {
    /**
     * We use the following graph to test AP.
     * 
     * 15-14 -13  12
     * . \      /    
     * 4 . 3 . 2  
     * |   .   |
     * 5   0 . 1  10
     * | \     .   .
     * 6   7 . 8 - 9 - 11
     */

    val similarities = Seq[(Long, Long, Double)]((0, 1, -8.2), (0, 3, -5.8), (1, 2, -0.4),
      (1, 8, -8.1), (2, 3, -9.2), (2, 12, -0.8), (3, 15, -1.5), (3, 4, -10.1), (4, 5, -0.7),
      (4, 15, -11.8), (5, 6, -0.7), (5, 7, -0.41), (7, 8, -8.1), (8, 9, -0.55), (9, 10, -5.8),
      (9, 11, -0.76), (13, 14, -0.15), (14, 15, -0.67), (0, 0, -1.3), (1, 1, -1.3), (2, 2, -1.3),
      (3, 3, -1.3), (4, 4, -1.3), (5, 5, -1.3), (6, 6, -1.3), (7, 7, -1.3), (8, 8, -1.3),
      (9, 9, -1.3),(10, 10, -1.3), (11, 11, -1.3), (12, 12, -1.3), (13, 13, -1.3), (14, 14, -1.3),
      (15, 15, -1.3))

    val model = new AffinityPropagation()
      .setMaxIterations(30)
      .run(sc.parallelize(similarities, 2))

    assert(model.k == 7)
    assert(model.findCluster(5).collect().sorted === Array[Long](4, 5, 6, 7))
    assert(model.findClusterID(14) != -1)
    assert(model.findClusterID(14) === model.findClusterID(15))
  }

  test("calculate preferences") {
    val similarities = Seq[(Long, Long, Double)]((0, 1, -8.2), (0, 3, -5.8), (1, 2, -0.4),
      (1, 8, -8.1), (2, 3, -9.2), (2, 12, -1.1), (3, 15, -1.5), (3, 4, -10.1), (4, 5, -0.7),
      (4, 15, -11.8), (5, 6, -0.7), (5, 7, -0.41), (7, 8, -8.1), (8, 9, -0.55), (9, 10, -5.8),
      (9, 11, -0.76), (13, 14, -0.15), (14, 15, -0.67))

    val ap = new AffinityPropagation()
    val similaritiesWithPreferneces =
      ap.determinePreferences(sc.parallelize(similarities, 2))

    def median(s: Seq[Double]): Double = {
      val (lower, upper) = s.sortWith(_<_).splitAt(s.size / 2)
      if (s.size % 2 == 0) (lower.last + upper.head) / 2.0 else upper.head
    }

    val medianValue = median(similarities.map(_._3))

    val preferences = similaritiesWithPreferneces.collect().filter(x => x._1 == x._2).map(_._3)
    preferences.foreach(p => assert(p == medianValue))

    val model = ap.setMaxIterations(30)
      .run(similaritiesWithPreferneces)

    assert(model.k == 7)
    assert(model.findCluster(5).collect().sorted === Array[Long](4, 5, 6, 7))
    assert(model.findClusterID(14) != -1)
    assert(model.findClusterID(14) === model.findClusterID(15))
    assert(model.findClusterID(100) == -1)
  }
 
  test("manually set up preferences") {
    val similarities = Seq[(Long, Long, Double)]((0, 1, -8.2), (0, 3, -5.8), (1, 2, -0.4),
      (1, 8, -8.1), (2, 3, -9.2), (2, 12, -1.1), (3, 15, -1.5), (3, 4, -10.1), (4, 5, -0.7),
      (4, 15, -11.8), (5, 6, -0.7), (5, 7, -0.41), (7, 8, -8.1), (8, 9, -0.55), (9, 10, -5.8),
      (9, 11, -0.76), (13, 14, -0.15), (14, 15, -0.67))

    val preference = -1.3

    val ap = new AffinityPropagation()
    val similaritiesWithPreferneces =
      ap.embedPreferences(sc.parallelize(similarities, 2), preference)

    val preferences = similaritiesWithPreferneces.collect().filter(x => x._1 == x._2).map(_._3)
    preferences.foreach(p => assert(p == preference))

    val model = ap.setMaxIterations(30)
      .run(similaritiesWithPreferneces)

    assert(model.k == 7)
    assert(model.findCluster(5).collect().sorted === Array[Long](4, 5, 6, 7))
    assert(model.findClusterID(14) != -1)
    assert(model.findClusterID(14) === model.findClusterID(15))
    assert(model.findClusterID(100) == -1)
  }

  test("normalize") {
    /**
     * Test normalize() with the following graph:
     *
     * 0 - 3
     * | \ |
     * 1 - 2
     *
     * The similarity matrix (A) is
     *
     * 0 1 1 1
     * 1 0 1 0
     * 1 1 0 1
     * 1 0 1 0
     *
     * D is diag(3, 2, 3, 2) and hence S is
     *
     * 0 1/3 1/3 1/3
     * 1/2   0 1/2   0
     * 1/3 1/3   0 1/3
     * 1/2   0 1/2   0
     */
    val similarities = Seq[(Long, Long, Double)](
      (0, 1, 1.0), (1, 0, 1.0), (0, 2, 1.0), (2, 0, 1.0), (0, 3, 1.0), (3, 0, 1.0),
      (1, 2, 1.0), (2, 1, 1.0), (2, 3, 1.0), (3, 2, 1.0))
    val expected = Array(
      Array(0.0,     1.0/3.0, 1.0/3.0, 1.0/3.0),
      Array(1.0/2.0,     0.0, 1.0/2.0,     0.0),
      Array(1.0/3.0, 1.0/3.0,     0.0, 1.0/3.0),
      Array(1.0/2.0,     0.0, 1.0/2.0,     0.0))
    val s = constructGraph(sc.parallelize(similarities, 2), true, false)
    s.edges.collect().foreach { case Edge(i, j, x) =>
      assert(math.abs(x.similarity - expected(i.toInt)(j.toInt)) < 1e-14)
    }
  }
}
