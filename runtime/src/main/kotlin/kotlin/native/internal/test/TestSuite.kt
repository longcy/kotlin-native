/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kotlin.native.internal.test

import kotlin.IllegalArgumentException
import kotlin.system.getTimeMillis
import kotlin.system.measureTimeMillis

public interface TestCase {
    val name: String
    val ignored: Boolean
    val suite: TestSuite

    fun run()
}

internal val TestCase.prettyName get() = "${suite.name}.$name"

public interface TestSuite {
    val name: String
    val ignored: Boolean
    val testCases: Map<String, TestCase>
    val size : Int

    fun doBeforeClass()
    fun doAfterClass()
}

public enum class TestFunctionKind {
    BEFORE_EACH,
    AFTER_EACH,
    BEFORE_CLASS,
    AFTER_CLASS
}

public abstract class AbstractTestSuite<F: Function<Unit>>(override val name: String, override val ignored: Boolean)
    : TestSuite {
    override fun toString(): String = name

    // TODO: Make inner and remove the type param when the bug is fixed.
    abstract class BasicTestCase<F: Function<Unit>>(
            override val name: String,
            override val suite: AbstractTestSuite<F>,
            val testFunction: F,
            override val ignored: Boolean
    ) : TestCase {
        override fun toString(): String = "$name ($suite)"
    }

    private val _testCases = mutableMapOf<String, BasicTestCase<F>>()
    override val testCases: Map<String, BasicTestCase<F>>
        get() = _testCases

    private fun registerTestCase(testCase: BasicTestCase<F>) = _testCases.put(testCase.name, testCase)

    fun registerTestCase(name: String, testFunction: F, ignored: Boolean) =
            registerTestCase(createTestCase(name, testFunction, ignored))

    abstract fun createTestCase(name: String, testFunction: F, ignored: Boolean):  BasicTestCase<F>

    init {
        registerSuite(this)
    }

    override val size: Int
        get() = testCases.size
}

public abstract class BaseClassSuite<INSTANCE, COMPANION>(name: String, ignored: Boolean)
    : AbstractTestSuite<INSTANCE.() -> Unit>(name, ignored) {

    class TestCase<INSTANCE, COMPANION>(name: String,
                                        override val suite: BaseClassSuite<INSTANCE, COMPANION>,
                                        testFunction: INSTANCE.() -> Unit,
                                        ignored: Boolean)
        : BasicTestCase<INSTANCE.() -> Unit>(name, suite, testFunction, ignored) {

        override fun run() {
            val instance = suite.createInstance()
            try {
                suite.before.forEach { instance.it() }
                instance.testFunction()
            } finally {
                suite.after.forEach { instance.it() }
            }
        }
    }

    // These two methods are overrided in test suite classes generated by the compiler.
    abstract fun createInstance(): INSTANCE
    open fun getCompanion(): COMPANION = throw NotImplementedError("Test class has no companion object")

    companion object {
        val INSTANCE_KINDS = listOf(TestFunctionKind.BEFORE_EACH, TestFunctionKind.AFTER_EACH)
        val COMPANION_KINDS = listOf(TestFunctionKind.BEFORE_CLASS, TestFunctionKind.AFTER_CLASS)
    }

    private val instanceFunctions = mutableMapOf<TestFunctionKind, MutableSet<INSTANCE.() -> Unit>>()
    private fun getInstanceFunctions(kind: TestFunctionKind): MutableCollection<INSTANCE.() -> Unit> {
        check(kind in INSTANCE_KINDS)
        return instanceFunctions.getOrPut(kind) { mutableSetOf() }
    }

    private val companionFunction = mutableMapOf<TestFunctionKind, MutableSet<COMPANION.() -> Unit>>()
    private fun getCompanionFunctions(kind: TestFunctionKind): MutableCollection<COMPANION.() -> Unit> {
        check(kind in COMPANION_KINDS)
        return companionFunction.getOrPut(kind) { mutableSetOf() }
    }

    val before:      Collection<INSTANCE.() -> Unit>  get() = getInstanceFunctions(TestFunctionKind.BEFORE_EACH)
    val after:       Collection<INSTANCE.() -> Unit>  get() = getInstanceFunctions(TestFunctionKind.AFTER_EACH)

    val beforeClass: Collection<COMPANION.() -> Unit>  get() = getCompanionFunctions(TestFunctionKind.BEFORE_CLASS)
    val afterClass:  Collection<COMPANION.() -> Unit>  get() = getCompanionFunctions(TestFunctionKind.AFTER_CLASS)

    @Suppress("UNCHECKED_CAST")
    fun registerFunction(kind: TestFunctionKind, function: Function1<*, Unit>) =
            when (kind) {
                in INSTANCE_KINDS -> getInstanceFunctions(kind).add(function as INSTANCE.() -> Unit)
                in COMPANION_KINDS -> getCompanionFunctions(kind).add(function as COMPANION.() -> Unit)
                else -> throw IllegalArgumentException("Unknown function kind: $kind")
            }

    override fun doBeforeClass() = beforeClass.forEach { getCompanion().it() }
    override fun doAfterClass() = afterClass.forEach { getCompanion().it() }

    override fun createTestCase(name: String, testFunction: INSTANCE.() -> Unit, ignored: Boolean)
            : BasicTestCase<INSTANCE.() -> Unit> =
            TestCase<INSTANCE, COMPANION>(name, this, testFunction, ignored)
}

private typealias TopLevelFun = () -> Unit

public class TopLevelSuite(name: String): AbstractTestSuite<TopLevelFun>(name, false) {

    class TestCase(name: String, override val suite: TopLevelSuite, testFunction: TopLevelFun, ignored: Boolean)
        : BasicTestCase<TopLevelFun>(name, suite, testFunction, ignored) {

        override fun run() {
            try {
                suite.before.forEach { it() }
                testFunction()
            } finally {
                suite.after.forEach { it() }
            }
        }
    }

    private val specialFunctions = mutableMapOf<TestFunctionKind, MutableSet<TopLevelFun>>()
    private fun getFunctions(type: TestFunctionKind) = specialFunctions.getOrPut(type) { mutableSetOf() }

    val before:      Collection<TopLevelFun>  get() = getFunctions(TestFunctionKind.BEFORE_EACH)
    val after:       Collection<TopLevelFun>  get() = getFunctions(TestFunctionKind.AFTER_EACH)
    val beforeClass: Collection<TopLevelFun>  get() = getFunctions(TestFunctionKind.BEFORE_CLASS)
    val afterClass:  Collection<TopLevelFun>  get() = getFunctions(TestFunctionKind.AFTER_CLASS)

    fun registerFunction(kind: TestFunctionKind, function: TopLevelFun) = getFunctions(kind).add(function)

    override fun doBeforeClass() = beforeClass.forEach { it() }
    override fun doAfterClass() = afterClass.forEach { it() }

    override fun createTestCase(name: String, testFunction: TopLevelFun, ignored: Boolean)
            : BasicTestCase<TopLevelFun> = TestCase(name, this, testFunction, ignored)
}