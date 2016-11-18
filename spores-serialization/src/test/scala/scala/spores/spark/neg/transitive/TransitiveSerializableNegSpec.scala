package scala.spores.spark.run.transitive

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

import scala.spores.spark.TestUtil._
import scala.spores.util.PluginFeedback._

@RunWith(classOf[JUnit4])
class TransitiveSerializableNegSpec {
  @Test
  def `Depth 1: Non serializable fields in classes are detected`(): Unit = {
    expectError(
      nonSerializableType("Foo", "value bar", "Bar")
    ) {
      """
        |import scala.spores._
        |class Bar
        |class Foo(val bar: Bar) extends Serializable
        |val foo = new Foo(new Bar)
        |spore {
        |  val captured = foo
        |  () => captured
        |}
      """.stripMargin
    }
  }

  @Test
  def `Depth 2: Non serializable fields in classes are detected`(): Unit = {
    expectError(
      nonSerializableType("Bar", "value baz", "Baz")
    ) {
      """
        |import scala.spores._
        |class Baz
        |class Bar(val baz: Baz) extends Serializable
        |class Foo(val bar: Bar) extends Serializable
        |val foo = new Foo(new Bar(new Baz {}))
        |spore {
        |  val captured = foo
        |  () => captured
        |}
      """.stripMargin
    }
  }

  @Test
  def `Depth 3: Non serializable fields in classes are detected`(): Unit = {
    expectError(
      nonSerializableType("Baz", "value baz2", "Baz2")
    ) {
      """
        |import scala.spores._
        |class Baz2
        |abstract class Baz(val baz2: Baz2) extends Serializable
        |class Bar(val baz: Baz) extends Serializable
        |class Foo(val bar: Bar) extends Serializable
        |val foo = new Foo(new Bar(new Baz(new Baz2 {}) {}))
        |spore {
        |  val captured = foo
        |  () => captured
        |}
      """.stripMargin
    }
  }

  @Test
  def `Depth 1: Non serializable fields in abstract classes are detected`(): Unit = {
    expectError(
      nonSerializableType("Foo", "value bar", "Bar")
    ) {
      """
        |import scala.spores._
        |abstract class Bar
        |class Foo(val bar: Bar) extends Serializable
        |val foo = new Foo(new Bar {})
        |spore {
        |  val captured = foo
        |  () => captured
        |}
      """.stripMargin
    }
  }

  @Test
  def `Depth 2: Non serializable fields in abstract classes are detected`(): Unit = {
    expectError(
      nonSerializableType("Bar", "value baz", "Baz")
    ) {
      """
        |import scala.spores._
        |abstract class Baz
        |class Bar(val baz: Baz) extends Serializable
        |class Foo(val bar: Bar) extends Serializable
        |val foo = new Foo(new Bar(new Baz {}))
        |spore {
        |  val captured = foo
        |  () => captured
        |}
      """.stripMargin
    }
  }

  @Test
  def `Depth 3: Non serializable fields in abstract classes are detected`(): Unit = {
    expectError(
      nonSerializableType("Baz", "value baz2", "Baz2")
    ) {
      """
        |import scala.spores._
        |abstract class Baz2
        |abstract class Baz(val baz2: Baz2) extends Serializable
        |class Bar(val baz: Baz) extends Serializable
        |class Foo(val bar: Bar) extends Serializable
        |val foo = new Foo(new Bar(new Baz(new Baz2 {}) {}))
        |spore {
        |  val captured = foo
        |  () => captured
        |}
      """.stripMargin
    }
  }
    @Test
  def `Depth 1: Non serializable fields in traits are detected`(): Unit = {
    expectError(
      nonSerializableType("Foo", "value bar", "Bar")
    ) {
      """
        |import scala.spores._
        |trait Bar
        |class Foo(val bar: Bar) extends Serializable
        |val foo = new Foo(new Bar {})
        |spore {
        |  val captured = foo
        |  () => captured
        |}
      """.stripMargin
    }
  }

  @Test
  def `Depth 2: Non serializable fields in traits are detected`(): Unit = {
    expectError(
      nonSerializableType("Bar", "value baz", "Baz")
    ) {
      """
        |import scala.spores._
        |trait Baz
        |class Bar(val baz: Baz) extends Serializable
        |class Foo(val bar: Bar) extends Serializable
        |val foo = new Foo(new Bar(new Baz {}))
        |spore {
        |  val captured = foo
        |  () => captured
        |}
      """.stripMargin
    }
  }

  @Test
  def `Depth 3: Non serializable fields in traits are detected`(): Unit = {
    expectError(
      nonSerializableType("Baz", "value baz2", "Baz2")
    ) {
      """
        |import scala.spores._
        |trait Baz2
        |abstract class Baz(val baz2: Baz2) extends Serializable
        |class Bar(val baz: Baz) extends Serializable
        |class Foo(val bar: Bar) extends Serializable
        |val foo = new Foo(new Bar(new Baz(new Baz2 {}) {}))
        |spore {
        |  val captured = foo
        |  () => captured
        |}
      """.stripMargin
    }
  }

  @Test
  def `Detect a wrapper around a function`(): Unit = {
    expectError(
      nonSerializableType("FakeWrapper", "value wrapped", "Int => Int")
    ) {
      """
        |import scala.spores._
        |class FakeWrapper(wrapped: Int => Int) extends Serializable
        |val foo = new FakeWrapper(i => i)
        |spore {
        |  val captured = foo
        |  () => captured
        |}
      """.stripMargin
    }
  }

  @Test
  def `Detect non serializable type in already applied type param`(): Unit = {
    expectError(
      nonSerializableType("Foo", "value value2", "Unserializable")
    ) {
      """
        |import scala.spores._
        |class Foo[T, U](value1: T, value2: U) extends Serializable
        |class Unserializable(val o: Object)
        |val noSerializable = new Unserializable("")
        |class PartialFoo[T](value: T) extends Foo(value, noSerializable)
        |val foo = new PartialFoo(1)
        |spore {
        |  val captured = foo
        |  () => captured
        |}
        |val test = new Foo(1, 2)
      """.stripMargin
    }
  }

  @Test
  def `Detect warning in type params not annotated with Serializable`(): Unit = {
    expectWarning(
      nonSerializableTypeParam("UnserializableTypeParam", "T")
    ) {
      """
        |import scala.spores._
        |class Foo
        |class UnserializableTypeParam[T](typedValue: T) extends Serializable
        |class Wrapper[T](outsider: T) {
        |  val foo = new UnserializableTypeParam(outsider)
        |  spore {
        |    val captured = foo
        |    () => captured
        |  }
        |}
        |val wrapped = new Wrapper(new Foo)
      """.stripMargin
    }
  }

  @Test
  def `Detect error in non-serializable tparams if force-serializable-type-params`(): Unit = {
    expectError(
      nonSerializableTypeParam("UnserializableTypeParam", "T"),
      "-P:spores-transitive-plugin:force-serializable-type-parameters"
    ) {
      """
        |import scala.spores._
        |class Foo
        |class UnserializableTypeParam[T](typedValue: T) extends Serializable
        |class Wrapper[T](outsider: T) {
        |  val foo = new UnserializableTypeParam(outsider)
        |  spore {
        |    val captured = foo
        |    () => captured
        |  }
        |}
        |val wrapped = new Wrapper(new Foo)
      """.stripMargin
    }
  }

  @Test
  def `Detect warning in type params annotated with Serializable`(): Unit = {
    expectWarning(
      stopInspection("SerializableTypeParam", "T", Some("SerializableTypeParam[T]"))
    ) {
      """
        |import scala.spores._
        |class Foo extends Serializable
        |class SerializableTypeParam[T <: Serializable](typedValue: T) extends Serializable
        |class Wrapper[T <: Serializable](outsider: T) {
        |  val foo = new SerializableTypeParam(outsider)
        |  spore {
        |    val captured = foo
        |    () => captured
        |  }
        |}
        |val wrapped = new Wrapper(new Foo)
      """.stripMargin
    }
  }

  @Test
  def `Detect error in concrete non-serializable type params`(): Unit = {
    expectError(
      nonSerializableType("SerializableTypeParam", "value typedValue", "Foo")
    ) {
      """
        |import scala.spores._
        |class Foo()
        |class SerializableTypeParam[T](typedValue: T) extends Serializable
        |val foo = new SerializableTypeParam(new Foo)
        |spore {
        |  val captured = foo
        |  () => captured
        |}
      """.stripMargin
    }
  }

  @Test
  def `Depth 1: Detect error in open class hierarchy if option is enabled`(): Unit = {
    expectError(openClassHierarchy("class SerializableTypeParam"), "-P:spores-transitive-plugin:force-closed-class-hierarchy") {
      """
        |import scala.spores._
        |class Foo extends Serializable
        |class SerializableTypeParam[T <: Serializable](typedValue: T) extends Serializable
        |val foo = new SerializableTypeParam(new Foo)
        |spore {
        |  val captured = foo
        |  () => captured
        |}
      """.stripMargin
    }
  }

  @Test
  def `Depth 2: Detect error in open class hierarchy if option is enabled`(): Unit = {
    expectError(openClassHierarchy("class Foo"), "-P:spores-transitive-plugin:force-closed-class-hierarchy") {
      """
        |import scala.spores._
        |class Foo extends Serializable
        |sealed class SerializableTypeParam[T <: Serializable](typedValue: T) extends Serializable
        |val foo = new SerializableTypeParam(new Foo)
        |spore {
        |  val captured = foo
        |  () => captured
        |}
      """.stripMargin
    }
  }

  @Test
  def `Detect non-serializable subclasses of sealed serializable subclass`(): Unit = {
    expectError(
      nonSerializableType("Trap", "value o", "Object")
    ) {
      """
        |import scala.spores._
        |sealed class Foo extends Serializable
        |final case class Trap(o: Object) extends Foo
        |class Trap2(o: Object) extends Foo
        |case class Trap3(o: Object) extends Trap2(o)
        |sealed class SerializableTypeParam[T <: Serializable](typedValue: T) extends Serializable
        |val foo = new SerializableTypeParam(new Foo)
        |spore {
        |  val captured = foo
        |  () => captured
        |}
      """.stripMargin
    }
  }

  @Test
  def `Detect warning if open hierarchy`(): Unit = {
    expectWarning(openClassHierarchy("class ::")) {
      """
        |import scala.spores._
        |sealed trait HList extends Product with Serializable
        |// Should be final, otherwise check is not complete.
        |case class ::[+H, +T <: HList](head : H, tail : T) extends HList
        |case object HNil extends HList
        |val foo = ::("", ::(1, HNil))
        |spore {
        |  val captured = foo
        |  () => captured
        |}
      """.stripMargin
    }
  }

  @Test
  def `Detect error in non-specified type parameter in recursive type`(): Unit = {
    expectError(
      stopInspection("::", "H", Some("::[T,::[T,HNil.type]]")),
      "-P:spores-transitive-plugin:force-transitive"
    ) {
      """
        |import scala.spores._
        |sealed trait HList extends Product with Serializable
        |final case class ::[+H, +T <: HList](head : H, tail : T) extends HList
        |case object HNil extends HList
        |
        |class Wrapper[T <: Serializable](outsider: T) {
        |  val foo = ::(outsider, ::(outsider, HNil))
        |  spore {
        |    val captured = foo
        |    () => captured
        |  }
        |}
      """.stripMargin
    }
  }

  @Test
  def `Detect warning in non-specified type parameter in recursive type`(): Unit = {
    expectWarning(
      stopInspection("::", "H", Some("::[T,::[T,HNil.type]]"))
    ) {
      """
        |import scala.spores._
        |sealed trait HList extends Product with Serializable
        |final case class ::[+H, +T <: HList](head : H, tail : T) extends HList
        |case object HNil extends HList
        |
        |class Wrapper[T <: Serializable](outsider: T) {
        |  val foo = ::(outsider, ::(outsider, HNil))
        |  spore {
        |    val captured = foo
        |    () => captured
        |  }
        |}
      """.stripMargin
    }
  }
}
