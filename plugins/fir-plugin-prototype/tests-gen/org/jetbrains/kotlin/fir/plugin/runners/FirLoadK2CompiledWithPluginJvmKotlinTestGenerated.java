/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.plugin.runners;

import com.intellij.testFramework.TestDataPath;
import org.jetbrains.kotlin.test.util.KtTestUtil;
import org.jetbrains.kotlin.test.TargetBackend;
import org.jetbrains.kotlin.test.TestMetadata;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.regex.Pattern;

/** This class is generated by {@link org.jetbrains.kotlin.generators.tests.GenerateTestsKt}. DO NOT MODIFY MANUALLY */
@SuppressWarnings("all")
@TestMetadata("plugins/fir-plugin-prototype/testData/firLoadK2Compiled")
@TestDataPath("$PROJECT_ROOT")
public class FirLoadK2CompiledWithPluginJvmKotlinTestGenerated extends AbstractFirLoadK2CompiledWithPluginJvmKotlinTest {
    @Test
    public void testAllFilesPresentInFirLoadK2Compiled() throws Exception {
        KtTestUtil.assertAllTestsPresentByMetadataWithExcluded(this.getClass(), new File("plugins/fir-plugin-prototype/testData/firLoadK2Compiled"), Pattern.compile("^(.+)\\.kt$"), null, TargetBackend.JVM_IR, true);
    }

    @Test
    @TestMetadata("annotationsGeneratedInBackend.kt")
    public void testAnnotationsGeneratedInBackend() throws Exception {
        runTest("plugins/fir-plugin-prototype/testData/firLoadK2Compiled/annotationsGeneratedInBackend.kt");
    }

    @Test
    @TestMetadata("annotationsGeneratedInBackend_MPP.kt")
    public void testAnnotationsGeneratedInBackend_MPP() throws Exception {
        runTest("plugins/fir-plugin-prototype/testData/firLoadK2Compiled/annotationsGeneratedInBackend_MPP.kt");
    }

    @Test
    @TestMetadata("simple.kt")
    public void testSimple() throws Exception {
        runTest("plugins/fir-plugin-prototype/testData/firLoadK2Compiled/simple.kt");
    }

    @Test
    @TestMetadata("simple-lang-ver-2.1.kt")
    public void testSimple_lang_ver_2_1() throws Exception {
        runTest("plugins/fir-plugin-prototype/testData/firLoadK2Compiled/simple-lang-ver-2.1.kt");
    }
}
