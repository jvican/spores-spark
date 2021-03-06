package scala.spores

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

import scala.pickling.Defaults._
import scala.pickling._
import scala.pickling.json._
import scala.spores.SporePicklers._

@RunWith(classOf[JUnit4])
class NullarySporePickling {

  @Test
  def `pickle/unpickle a NullarySpore`(): Unit = {
    val ns: NullarySpore[Int] = spore {
      () => 1
    }
    val pickled = ns.pickle
    val ns2 = pickled.unpickle[NullarySpore[Int]]
    assert(ns() == ns2())
  }

  @Test
  def `pickle/unpickle a NullarySpore capturing Nothing`(): Unit = {
    val ns: NullarySpore[Int] { type Captured = Nothing } = spore {
      () => 1
    }
    val pickled = ns.pickle
    val ns2 = pickled.unpickle[NullarySpore[Int] {type Captured = Nothing}]
    assert(ns() == ns2())
  }

  @Test
  def `pickle/unpickle a NullarySpore with environment`(): Unit = {
    val b = 2
    val ns: NullarySporeWithEnv[Int] {type Captured = Int} = spore {
      val num = b
      () => 1 + num
    }
    val pickled = ns.pickle
    println(pickled)
    val ns2 = pickled.unpickle[NullarySporeWithEnv[Int] {type Captured = Int}]
    assert(ns() == ns2())
  }
}
