// WITH_STDLIB
// FIR_IDENTICAL

// FILE: annotation.kt
package kotlinx.serialization

import kotlin.annotation.*

/*
  Until the annotation is added to the serialization runtime,
  we have to create an annotation with that name in the project itself
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class KeepGeneratedSerializer

// FILE: main.kt
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import kotlinx.serialization.encoding.*
import kotlinx.serialization.descriptors.*

// == final class ==
@Serializable(with = DataSerializer::class)
@KeepGeneratedSerializer
class Data(val i: Int)

object DataSerializer: ToDoSerializer<Data>("DataSerializer") {
    override fun serialize(encoder: Encoder, value: Data) {
        encoder.encodeInt(value.i)
    }
}

// == inheritance ==
@Serializable(with = ParentSerializer::class)
@KeepGeneratedSerializer
open class Parent(val p: Int)

object ParentSerializer: ToDoSerializer<Parent>("ParentSerializer") {
    override fun serialize(encoder: Encoder, value: Parent) {
        encoder.encodeInt(value.p)
    }
}

@Serializable
class Child(val c: Int): Parent(0)

@Serializable(with = ChildSerializer::class)
@KeepGeneratedSerializer
class ChildWithCustom(val c: Int): Parent(0)

object ChildSerializer: ToDoSerializer<ChildWithCustom>("ChildSerializer") {
    override fun serialize(encoder: Encoder, value: ChildWithCustom) {
        encoder.encodeInt(value.c)
    }
}

// == enums ==
@Serializable(with = MyEnumSerializer::class)
@KeepGeneratedSerializer
enum class MyEnum {
    A,
    B,
    FALLBACK
}

@Serializable
class EnumHolder(val e: MyEnum)

object MyEnumSerializer: ToDoSerializer<MyEnum>("MyEnumSerializer") {
    val defaultSerializer = MyEnum.generatedSerializer()

    override fun serialize(encoder: Encoder, value: MyEnum) {
        // always encode FALLBACK entry by generated serializer
        defaultSerializer.serialize(encoder, MyEnum.FALLBACK)
    }
}

// == parametrized ==
@Serializable(with = ParametrizedSerializer::class)
@KeepGeneratedSerializer
class ParametrizedData<T>(val t: T)

class ParametrizedSerializer(val serializer: KSerializer<Any>): ToDoSerializer<ParametrizedData<Any>>("ParametrizedSerializer") {
    override fun serialize(encoder: Encoder, value: ParametrizedData<Any>) {
        serializer.serialize(encoder, value.t)
    }
}


fun box(): String {
    val data = Data(42)
    val child = Child(1)
    val childCustom = ChildWithCustom(2)
    val myEnum = MyEnum.A
    val param = ParametrizedData<Data>(data)

    val jsonImplicit = Json.encodeToString(data)
    val json = Json.encodeToString(Data.serializer(), data)
    val jsonGenerated = Json.encodeToString(Data.generatedSerializer(), data)
    if (jsonImplicit != "42") {
        return "JSON Implicit = " + jsonImplicit
    }
    if (json != "42") {
        return "JSON = " + json
    }
    if (jsonGenerated != "{\"i\":42}") {
        return "JSON Generated = " + jsonImplicit
    }

    val childJsonImplicit = Json.encodeToString(child)
    val childJson = Json.encodeToString(Child.serializer(), child)
    if (childJsonImplicit != "{\"p\":0,\"c\":1}") {
        return "Child JSON Implicit = " + childJsonImplicit
    }
    if (childJson != "{\"p\":0,\"c\":1}") {
        return "Child JSON = " + childJson
    }

    val childCustomJsonImplicit = Json.encodeToString(childCustom)
    val childCustomJson = Json.encodeToString(ChildWithCustom.serializer(), childCustom)
    val childCustomJsonGenerated = Json.encodeToString(ChildWithCustom.generatedSerializer(), childCustom)
    if (childCustomJsonImplicit != "2") {
        return "Child with custom serializer JSON Implicit = " + childCustomJsonImplicit
    }
    if (childCustomJson != "2") {
        return "Child with custom serializer JSON = " + childCustomJson
    }
    if (childCustomJsonGenerated != "{\"p\":0,\"c\":2}") {
        return "Child with custom serializer JSON Generated = " + childCustomJsonGenerated
    }

    val enumJsonImplicit = Json.encodeToString(myEnum)
    val enumJson = Json.encodeToString(MyEnum.serializer(), myEnum)
    val enumJsonGenerated = Json.encodeToString(MyEnum.generatedSerializer(), myEnum)

    if (enumJsonImplicit != "\"FALLBACK\"") {
        return "Enum JSON Implicit = " + enumJsonImplicit
    }
    if (enumJson != "\"FALLBACK\"") {
        return "Enum JSON = " + enumJson
    }
    if (enumJsonGenerated != "\"A\"") {
        return "Enum JSON Generated = " + enumJsonGenerated
    }

    if (serializer<MyEnum>() !is MyEnumSerializer) {
        return "serializer<MyEnum> illegal = " + serializer<MyEnum>()
    }
    if (MyEnum.serializer() !is MyEnumSerializer) {
        return "MyEnum.serializer() illegal = " + MyEnum.serializer()
    }
    if (MyEnum.generatedSerializer().toString() != "kotlinx.serialization.internal.EnumSerializer<MyEnum>") {
        return "MyEnum.generatedSerializer() illegal = " + MyEnum.generatedSerializer()
    }

    if (MyEnum.generatedSerializer() !== MyEnum.generatedSerializer()) return "MyEnum.generatedSerializer() instance differs"

    val paramJsonImplicit = Json.encodeToString(param)
    val paramJson = Json.encodeToString(ParametrizedData.serializer(Data.serializer()), param)
    val paramJsonGenerated = Json.encodeToString(ParametrizedData.generatedSerializer(Data.serializer()), param)
    if (paramJsonImplicit != "42") {
        return "Parametrized JSON Implicit = " + paramJsonImplicit
    }
    if (paramJson != "42") {
        return "Parametrized JSON = " + paramJson
    }
    if (paramJsonGenerated != "{\"t\":42}") {
        return "Parametrized JSON Generated = " + paramJsonGenerated
    }

    return "OK"
}

abstract class ToDoSerializer<T>(name: String): KSerializer<T> {
    override val descriptor = PrimitiveSerialDescriptor(name, PrimitiveKind.STRING)
    open override fun deserialize(decoder: Decoder): T { TODO() }
    open override fun serialize(encoder: Encoder, value: T) { TODO() }
}
