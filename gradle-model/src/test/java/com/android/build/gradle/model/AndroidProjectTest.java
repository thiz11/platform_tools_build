/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.build.gradle.model;

import com.android.annotations.NonNull;
import com.android.builder.model.AndroidLibrary;
import com.android.builder.model.ProductFlavor;
import com.android.builder.model.SourceProvider;
import junit.framework.TestCase;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.GradleProject;

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.CodeSource;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AndroidProjectTest extends TestCase {

    public void testBasic() {
        // Configure the connector and create the connection
        GradleConnector connector = GradleConnector.newConnector();

        File projectDir = new File(getTestDir(), "basic");
        connector.forProjectDirectory(projectDir);

        ProjectConnection connection = connector.connect();
        try {
            // Load the custom model for the project
            AndroidProject model = connection.getModel(AndroidProject.class);
            assertNotNull("Model Object null-check", model);
            assertEquals("Model Name", "basic", model.getName());
            assertFalse("Library Project", model.isLibrary());
            assertEquals("Compile Target", "android-15", model.getCompileTarget());
            assertFalse("Non empty bootclasspath", model.getBootClasspath().isEmpty());

            ProductFlavorContainer defaultConfig = model.getDefaultConfig();

            new SourceProviderTester(model.getName(), projectDir,
                    "main", defaultConfig.getSourceProvider())
                    .test();
            new SourceProviderTester(model.getName(), projectDir,
                    "instrumentTest", defaultConfig.getTestSourceProvider())
                    .test();

            Map<String, BuildTypeContainer> buildTypes = model.getBuildTypes();
            assertEquals("Build Type Count", 2, buildTypes.size());

            Map<String, Variant> variants = model.getVariants();
            assertEquals("Variant Count", 2 , variants.size());

            // debug variant
            Variant debugVariant = variants.get("Debug");
            assertNotNull("Debug Variant null-check", debugVariant);
            new ProductFlavorTester(debugVariant.getMergedFlavor(), "Debug Merged Flavor")
                    .setVersionCode(12)
                    .setVersionName("2.0")
                    .setMinSdkVersion(16)
                    .setTargetSdkVersion(16)
                    .test();

            // this variant is tested.
            assertNotNull("Debug assemble test task null-check",
                    debugVariant.getAssembleTestTaskName());
            assertNotNull("Debug test output file null-check", debugVariant.getOutputTestFile());
            assertTrue("Debug signed check", debugVariant.isSigned());

            // release variant, not tested.
            Variant releaseVariant = variants.get("Release");
            assertNotNull("Release Variant null-check", releaseVariant);

            assertNull("Release assemble test task null-check",
                    releaseVariant.getAssembleTestTaskName());
            assertNull("Release test output file null-check", releaseVariant.getOutputTestFile());
            assertFalse("Release signed check", releaseVariant.isSigned());

            Dependencies dependencies = model.getVariants().get("Debug").getDependencies();
            assertNotNull(dependencies);
            assertEquals(2, dependencies.getJars().size());
            assertEquals(1, dependencies.getLibraries().size());
            assertTrue(dependencies.getProjectDependenciesPath().isEmpty());

        } finally {
            // Clean up
            connection.close();
        }
    }

    public void testMigrated() {
        // Configure the connector and create the connection
        GradleConnector connector = GradleConnector.newConnector();

        File projectDir = new File(getTestDir(), "migrated");
        connector.forProjectDirectory(projectDir);

        ProjectConnection connection = connector.connect();
        try {
            // Load the custom model for the project
            AndroidProject model = connection.getModel(AndroidProject.class);
            assertNotNull("Model Object null-check", model);
            assertEquals("Model Name", "migrated", model.getName());
            assertFalse("Library Project", model.isLibrary());

            ProductFlavorContainer defaultConfig = model.getDefaultConfig();

            new SourceProviderTester(model.getName(), projectDir,
                    "main", defaultConfig.getSourceProvider())
                    .setJavaDir("src")
                    .setResourcesDir("src")
                    .setAidlDir("src")
                    .setRenderscriptDir("src")
                    .setResDir("res")
                    .setAssetsDir("assets")
                    .setManifestFile("AndroidManifest.xml")
                    .test();

            new SourceProviderTester(model.getName(), projectDir,
                    "instrumentTest", defaultConfig.getTestSourceProvider())
                    .setJavaDir("tests/java")
                    .setResourcesDir("tests/resources")
                    .setAidlDir("tests/aidl")
                    .setJniDir("tests/jni")
                    .setRenderscriptDir("tests/rs")
                    .setResDir("tests/res")
                    .setAssetsDir("tests/assets")
                    .setManifestFile("tests/AndroidManifest.xml")
                    .test();

        } finally {
            // Clean up
            connection.close();
        }
    }

    public void testRenamedApk() {
        // Configure the connector and create the connection
        GradleConnector connector = GradleConnector.newConnector();

        File projectDir = new File(getTestDir(), "renamedApk");
        connector.forProjectDirectory(projectDir);

        ProjectConnection connection = connector.connect();
        try {
            // Load the custom model for the project
            AndroidProject model = connection.getModel(AndroidProject.class);
            assertNotNull("Model Object null-check", model);
            assertEquals("Model Name", "renamedApk", model.getName());

            Map<String, Variant> variants = model.getVariants();
            assertEquals("Variant Count", 2 , variants.size());

            File buildDir = new File(projectDir, "build");

            for (Variant variant : variants.values()) {
                assertEquals("Output file for " + variant.getName(),
                        new File(buildDir, variant.getName() + ".apk"),
                        variant.getOutputFile());
            }

        } finally {
            // Clean up
            connection.close();
        }
    }

    public void testFlavors() {
        // Configure the connector and create the connection
        GradleConnector connector = GradleConnector.newConnector();

        File projectDir = new File(getTestDir(), "flavors");
        connector.forProjectDirectory(projectDir);

        ProjectConnection connection = connector.connect();
        try {
            // Load the custom model for the project
            AndroidProject model = connection.getModel(AndroidProject.class);
            assertNotNull("Model Object null-check", model);
            assertEquals("Model Name", "flavors", model.getName());
            assertFalse("Library Project", model.isLibrary());

            ProductFlavorContainer defaultConfig = model.getDefaultConfig();

            new SourceProviderTester(model.getName(), projectDir,
                    "main", defaultConfig.getSourceProvider())
                    .test();
            new SourceProviderTester(model.getName(), projectDir,
                    "instrumentTest", defaultConfig.getTestSourceProvider())
                    .test();

            Map<String, BuildTypeContainer> buildTypes = model.getBuildTypes();
            assertEquals("Build Type Count", 2, buildTypes.size());

            Map<String, Variant> variants = model.getVariants();
            assertEquals("Variant Count", 8 , variants.size());

            Variant f1faDebugVariant = variants.get("F1FaDebug");
            assertNotNull("F1faDebug Variant null-check", f1faDebugVariant);
            new ProductFlavorTester(f1faDebugVariant.getMergedFlavor(), "F1faDebug Merged Flavor")
                    .test();
            new VariantTester(f1faDebugVariant, projectDir, "flavors-f1fa-debug-unaligned.apk").test();


        } finally {
            // Clean up
            connection.close();
        }
    }

    public void testTicTacToe() {
        // Configure the connector and create the connection
        GradleConnector connector = GradleConnector.newConnector();

        File projectDir = new File(getTestDir(), "tictactoe");
        connector.forProjectDirectory(projectDir);

        ProjectConnection connection = connector.connect();
        try {
            GradleProject model = connection.getModel(GradleProject.class);
            assertNotNull("Model Object null-check", model);

            for (GradleProject child : model.getChildren()) {
                String path = child.getPath();
                String name = path.substring(1);
                File childDir = new File(projectDir, name);

                GradleConnector childConnector = GradleConnector.newConnector();

                childConnector.forProjectDirectory(childDir);

                ProjectConnection childConnection = childConnector.connect();
                try {
                    AndroidProject androidProject = childConnection.getModel(AndroidProject.class);
                    assertNotNull("Model Object null-check", androidProject);
                    assertEquals("Model Name", name, androidProject.getName());
                    assertEquals("Library Project", "lib".equals(name), androidProject.isLibrary());

                    if (!"lib".equals(name)) {
                        Dependencies dependencies = androidProject.getVariants().get("Debug").getDependencies();
                        assertNotNull(dependencies);

                        List<AndroidLibrary> libs = dependencies.getLibraries();
                        assertNotNull(libs);

                        assertEquals(1, libs.size());
                        AndroidLibrary androidLibrary = libs.get(0);
                        assertNotNull(androidLibrary);
                        // TODO: right now we can only test the folder name efficiently
                        assertEquals("TictactoeLibUnspecified.aar", androidLibrary.getFolder().getName());
                    }
                } finally {
                    childConnection.close();
                }

            }
        } finally {
            // Clean up
            connection.close();
        }
    }

    public void testFlavorLib() {
        // Configure the connector and create the connection
        GradleConnector connector = GradleConnector.newConnector();

        File projectDir = new File(getTestDir(), "flavorlib");
        connector.forProjectDirectory(projectDir);

        ProjectConnection connection = connector.connect();
        try {
            GradleProject model = connection.getModel(GradleProject.class);
            assertNotNull("Model Object null-check", model);

            for (GradleProject child : model.getChildren()) {
                String path = child.getPath();
                String name = path.substring(1);

                if ("app".equals(name)) {
                    File childDir = new File(projectDir, name);

                    GradleConnector childConnector = GradleConnector.newConnector();
                    childConnector.forProjectDirectory(childDir);

                    ProjectConnection childConnection = childConnector.connect();

                    try {
                        AndroidProject androidProject = childConnection.getModel(AndroidProject.class);
                        assertNotNull("Model Object null-check", androidProject);
                        assertEquals("Model Name", name, androidProject.getName());
                        assertFalse("Library Project", androidProject.isLibrary());

                        Map<String, Variant> variants = androidProject.getVariants();

                        ProductFlavorContainer flavor1 = androidProject.getProductFlavors().get("flavor1");
                        assertNotNull(flavor1);

                        Variant flavor1Debug = variants.get("Flavor1Debug");
                        assertNotNull(flavor1Debug);

                        Dependencies dependencies = flavor1Debug.getDependencies();
                        assertNotNull(dependencies);
                        List<AndroidLibrary> libs = dependencies.getLibraries();
                        assertNotNull(libs);
                        assertEquals(1, libs.size());
                        AndroidLibrary androidLibrary = libs.get(0);
                        assertNotNull(androidLibrary);
                        // TODO: right now we can only test the folder name efficiently
                        assertEquals("FlavorlibLib1Unspecified.aar", androidLibrary.getFolder().getName());

                        ProductFlavorContainer flavor2 = androidProject.getProductFlavors().get("flavor2");
                        assertNotNull(flavor2);

                        Variant flavor2Debug = variants.get("Flavor2Debug");
                        assertNotNull(flavor2Debug);

                        dependencies = flavor2Debug.getDependencies();
                        assertNotNull(dependencies);
                        libs = dependencies.getLibraries();
                        assertNotNull(libs);
                        assertEquals(1, libs.size());
                        androidLibrary = libs.get(0);
                        assertNotNull(androidLibrary);
                        // TODO: right now we can only test the folder name efficiently
                        assertEquals("FlavorlibLib2Unspecified.aar", androidLibrary.getFolder().getName());

                    } finally {
                        childConnection.close();
                    }

                    break;
                }
            }
        } finally {
            // Clean up
            connection.close();
        }
    }

    /**
     * Returns the SDK folder as built from the Android source tree.
     * @return the SDK
     */
    protected File getSdkDir() {
        String androidHome = System.getenv("ANDROID_HOME");
        if (androidHome != null) {
            File f = new File(androidHome);
            if (f.isDirectory()) {
                return f;
            }
        }

        throw new IllegalStateException("SDK not defined with ANDROID_HOME");
    }

    /**
     * Returns the root dir for the gradle plugin project
     */
    private File getRootDir() {
        CodeSource source = getClass().getProtectionDomain().getCodeSource();
        if (source != null) {
            URL location = source.getLocation();
            try {
                File dir = new File(location.toURI());
                assertTrue(dir.getPath(), dir.exists());

                File f= dir.getParentFile().getParentFile().getParentFile().getParentFile().getParentFile().getParentFile().getParentFile().getParentFile();
                return  new File(f, "tools" + File.separator + "build");
            } catch (URISyntaxException e) {
                fail(e.getLocalizedMessage());
            }
        }

        fail("Fail to get the tools/build folder");
        return null;
    }

    /**
     * Returns the root folder for the tests projects.
     */
    private File getTestDir() {
        File rootDir = getRootDir();
        return new File(rootDir, "tests");
    }

    private static final class ProductFlavorTester {
        @NonNull private final ProductFlavor productFlavor;
        @NonNull private final String name;

        private String packageName = null;
        private int versionCode = -1;
        private String versionName = null;
        private int minSdkVersion = -1;
        private int targetSdkVersion = -1;
        private int renderscriptTargetApi = -1;
        private String testPackageName = null;
        private String testInstrumentationRunner = null;

        ProductFlavorTester(@NonNull ProductFlavor productFlavor, @NonNull String name) {
            this.productFlavor = productFlavor;
            this.name = name;
        }

        ProductFlavorTester setPackageName(String packageName) {
            this.packageName = packageName;
            return this;
        }

        ProductFlavorTester setVersionCode(int versionCode) {
            this.versionCode = versionCode;
            return this;
        }

         ProductFlavorTester setVersionName(String versionName) {
            this.versionName = versionName;
            return this;
        }

         ProductFlavorTester setMinSdkVersion(int minSdkVersion) {
            this.minSdkVersion = minSdkVersion;
            return this;
        }

         ProductFlavorTester setTargetSdkVersion(int targetSdkVersion) {
            this.targetSdkVersion = targetSdkVersion;
            return this;
        }

         ProductFlavorTester setRenderscriptTargetApi(int renderscriptTargetApi) {
            this.renderscriptTargetApi = renderscriptTargetApi;
            return this;
        }

         ProductFlavorTester setTestPackageName(String testPackageName) {
            this.testPackageName = testPackageName;
            return this;
        }

         ProductFlavorTester setTestInstrumentationRunner(String testInstrumentationRunner) {
            this.testInstrumentationRunner = testInstrumentationRunner;
            return this;
        }

        void test() {
            assertEquals(name + ":packageName", packageName, productFlavor.getPackageName());
            assertEquals(name + ":VersionCode", versionCode, productFlavor.getVersionCode());
            assertEquals(name + ":VersionName", versionName, productFlavor.getVersionName());
            assertEquals(name + ":minSdkVersion", minSdkVersion, productFlavor.getMinSdkVersion());
            assertEquals(name + ":targetSdkVersion",
                    targetSdkVersion, productFlavor.getTargetSdkVersion());
            assertEquals(name + ":renderscriptTargetApi",
                    renderscriptTargetApi, productFlavor.getRenderscriptTargetApi());
            assertEquals(name + ":testPackageName",
                    testPackageName, productFlavor.getTestPackageName());
            assertEquals(name + ":testInstrumentationRunner",
                    testInstrumentationRunner, productFlavor.getTestInstrumentationRunner());
        }
    }

    private static final class SourceProviderTester {

        @NonNull private final String projectName;
        @NonNull private final String configName;
        @NonNull private final SourceProvider sourceProvider;
        @NonNull private final File projectDir;
        private String javaDir;
        private String resourcesDir;
        private String manifestFile;
        private String resDir;
        private String assetsDir;
        private String aidlDir;
        private String renderscriptDir;
        private String jniDir;

        SourceProviderTester(@NonNull String projectName, @NonNull File projectDir,
                             @NonNull String configName, @NonNull SourceProvider sourceProvider) {
            this.projectName = projectName;
            this.projectDir = projectDir;
            this.configName = configName;
            this.sourceProvider = sourceProvider;
            // configure tester with default relative paths
            setJavaDir("src/" + configName + "/java");
            setResourcesDir("src/" + configName + "/resources");
            setManifestFile("src/" + configName + "/AndroidManifest.xml");
            setResDir("src/" + configName + "/res");
            setAssetsDir("src/" + configName + "/assets");
            setAidlDir("src/" + configName + "/aidl");
            setRenderscriptDir("src/" + configName + "/rs");
            setJniDir("src/" + configName + "/jni");
        }

        SourceProviderTester setJavaDir(String javaDir) {
            this.javaDir = javaDir;
            return this;
        }

        SourceProviderTester setResourcesDir(String resourcesDir) {
            this.resourcesDir = resourcesDir;
            return this;
        }

        SourceProviderTester setManifestFile(String manifestFile) {
            this.manifestFile = manifestFile;
            return this;
        }

        SourceProviderTester setResDir(String resDir) {
            this.resDir = resDir;
            return this;
        }

        SourceProviderTester setAssetsDir(String assetsDir) {
            this.assetsDir = assetsDir;
            return this;
        }

        SourceProviderTester setAidlDir(String aidlDir) {
            this.aidlDir = aidlDir;
            return this;
        }

        SourceProviderTester setRenderscriptDir(String renderscriptDir) {
            this.renderscriptDir = renderscriptDir;
            return this;
        }

        SourceProviderTester setJniDir(String jniDir) {
            this.jniDir = jniDir;
            return this;
        }

        void test() {
            testSinglePathSet("java", javaDir, sourceProvider.getJavaDirectories());
            testSinglePathSet("resources", resourcesDir, sourceProvider.getResourcesDirectories());
            testSinglePathSet("res", resDir, sourceProvider.getResDirectories());
            testSinglePathSet("assets", assetsDir, sourceProvider.getAssetsDirectories());
            testSinglePathSet("aidl", aidlDir, sourceProvider.getAidlDirectories());
            testSinglePathSet("rs", renderscriptDir, sourceProvider.getRenderscriptDirectories());
            testSinglePathSet("jni", jniDir, sourceProvider.getJniDirectories());

            assertEquals("AndroidManifest",
                    new File(projectDir, manifestFile).getAbsolutePath(),
                    sourceProvider.getManifestFile().getAbsolutePath());
        }

        private void testSinglePathSet(String setName, String referencePath, Set<File> pathSet) {
            assertEquals(1, pathSet.size());
            assertEquals(projectName + ": " + configName + "/" + setName,
                    new File(projectDir, referencePath).getAbsolutePath(),
                    pathSet.iterator().next().getAbsolutePath());
        }

    }

    private static final class VariantTester {

        private final Variant variant;
        private final File projectDir;
        private final String outputFileName;

        VariantTester(Variant variant, File projectDir, String outputFileName) {
            this.variant = variant;
            this.projectDir = projectDir;
            this.outputFileName = outputFileName;
        }

        void test() {
            String variantName = variant.getName();
            File build = new File(projectDir,  "build");
            File apk = new File(build, "apk/" + outputFileName);
            assertEquals(variantName + " output", apk, variant.getOutputFile());

            List<File> sourceFolders = variant.getGeneratedSourceFolders();
            assertEquals("Gen src Folder count", 4, sourceFolders.size());
        }
    }
}
