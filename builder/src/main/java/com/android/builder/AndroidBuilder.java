/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.builder;

import com.android.SdkConstants;
import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.annotations.VisibleForTesting;
import com.android.builder.compiling.DependencyFileProcessor;
import com.android.builder.dependency.ManifestDependency;
import com.android.builder.dependency.SymbolFileProvider;
import com.android.builder.internal.BuildConfigGenerator;
import com.android.builder.internal.SymbolLoader;
import com.android.builder.internal.SymbolWriter;
import com.android.builder.internal.TestManifestGenerator;
import com.android.builder.internal.compiler.AidlProcessor;
import com.android.builder.internal.compiler.FileGatherer;
import com.android.builder.internal.compiler.LeafFolderGatherer;
import com.android.builder.internal.compiler.SourceSearcher;
import com.android.builder.internal.packaging.JavaResourceProcessor;
import com.android.builder.internal.packaging.Packager;
import com.android.builder.model.AaptOptions;
import com.android.builder.model.SigningConfig;
import com.android.builder.packaging.DuplicateFileException;
import com.android.builder.packaging.PackagerException;
import com.android.builder.packaging.SealedPackageException;
import com.android.builder.packaging.SigningException;
import com.android.builder.signing.CertificateInfo;
import com.android.builder.signing.KeystoreHelper;
import com.android.builder.signing.KeytoolException;
import com.android.ide.common.internal.AaptRunner;
import com.android.ide.common.internal.CommandLineRunner;
import com.android.ide.common.internal.LoggedErrorException;
import com.android.manifmerger.ManifestMerger;
import com.android.manifmerger.MergerLog;
import com.android.sdklib.BuildToolInfo;
import com.android.sdklib.IAndroidTarget;
import com.android.sdklib.repository.FullRevision;
import com.android.utils.ILogger;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.io.Files;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * This is the main builder class. It is given all the data to process the build (such as
 * {@link DefaultProductFlavor}s, {@link DefaultBuildType} and dependencies) and use them when doing specific
 * build steps.
 *
 * To use:
 * create a builder with {@link #AndroidBuilder(SdkParser, String, ILogger, boolean)}
 *
 * then build steps can be done with
 * {@link #generateBuildConfig(String, boolean, java.util.List, String)}
 * {@link #processManifest(java.io.File, java.util.List, java.util.List, String, int, String, int, int, String)}
 * {@link #processTestManifest(String, int, int, String, String, java.util.List, String)}
 * {@link #processResources(java.io.File, java.io.File, java.io.File, java.util.List, String, String, String, String, String, com.android.builder.VariantConfiguration.Type, boolean, com.android.builder.model.AaptOptions)}
 * {@link #compileAllAidlFiles(java.util.List, java.io.File, java.util.List, com.android.builder.compiling.DependencyFileProcessor)}
 * {@link #convertByteCode(Iterable, Iterable, File, String, DexOptions, boolean)}
 * {@link #packageApk(String, String, java.util.List, String, String, boolean, SigningConfig, String)}
 *
 * Java compilation is not handled but the builder provides the bootclasspath with
 * {@link #getBootClasspath(SdkParser)}.
 */
public class AndroidBuilder {

    private static final FullRevision MIN_BUILD_TOOLS_REV = new FullRevision(16, 0, 0);

    private static final DependencyFileProcessor sNoOpDependencyFileProcessor = new DependencyFileProcessor() {
        @Override
        public boolean processFile(@NonNull File dependencyFile) {
            return true;
        }
    };

    private final SdkParser mSdkParser;
    private final ILogger mLogger;
    private final CommandLineRunner mCmdLineRunner;
    private final boolean mVerboseExec;

    @NonNull
    private final IAndroidTarget mTarget;
    @NonNull
    private final BuildToolInfo mBuildTools;
    private String mCreatedBy;

    /**
     * Creates an AndroidBuilder
     * <p/>
     * This receives an {@link SdkParser} to provide the build with information about the SDK, as
     * well as an {@link ILogger} to display output.
     * <p/>
     * <var>verboseExec</var> is needed on top of the ILogger due to remote exec tools not being
     * able to output info and verbose messages separately.
     *
     * @param sdkParser the SdkParser
     * @param logger the Logger
     * @param verboseExec whether external tools are launched in verbose mode
     */
    public AndroidBuilder(
            @NonNull SdkParser sdkParser,
            @Nullable String createdBy,
            @NonNull ILogger logger,
            boolean verboseExec) {
        mCreatedBy = createdBy;
        mSdkParser = checkNotNull(sdkParser);
        mLogger = checkNotNull(logger);
        mVerboseExec = verboseExec;
        mCmdLineRunner = new CommandLineRunner(mLogger);

        BuildToolInfo buildToolInfo = mSdkParser.getBuildTools();
        FullRevision buildToolsRevision = buildToolInfo.getRevision();

        if (buildToolsRevision.compareTo(MIN_BUILD_TOOLS_REV) < 0) {
            throw new IllegalArgumentException(String.format(
                    "The SDK Build Tools revision (%1$s) is too low. Minimum required is %2$s",
                    buildToolsRevision, MIN_BUILD_TOOLS_REV));
        }

        mTarget = mSdkParser.getTarget();
        mBuildTools = mSdkParser.getBuildTools();
    }

    @VisibleForTesting
    AndroidBuilder(
            @NonNull SdkParser sdkParser,
            @NonNull CommandLineRunner cmdLineRunner,
            @NonNull ILogger logger,
            boolean verboseExec) {
        mSdkParser = checkNotNull(sdkParser);
        mCmdLineRunner = checkNotNull(cmdLineRunner);
        mLogger = checkNotNull(logger);
        mVerboseExec = verboseExec;

        mTarget = mSdkParser.getTarget();
        mBuildTools = mSdkParser.getBuildTools();
    }

    /**
     * Helper method to get the boot classpath to be used during compilation.
     */
    public static List<String> getBootClasspath(@NonNull SdkParser sdkParser) {

        List<String> classpath = Lists.newArrayList();

        IAndroidTarget target = sdkParser.getTarget();

        classpath.addAll(target.getBootClasspath());

        // add optional libraries if any
        IAndroidTarget.IOptionalLibrary[] libs = target.getOptionalLibraries();
        if (libs != null) {
            for (IAndroidTarget.IOptionalLibrary lib : libs) {
                classpath.add(lib.getJarPath());
            }
        }

        // add annotations.jar if needed.
        if (target.getVersion().getApiLevel() <= 15) {
            classpath.add(sdkParser.getAnnotationsJar());
        }

        return classpath;
    }


    /**
     * Returns an {@link AaptRunner} able to run aapt commands.
     * @return an AaptRunner object
     */
    public AaptRunner getAaptRunner() {
        return new AaptRunner(
                mBuildTools.getPath(BuildToolInfo.PathId.AAPT),
                mCmdLineRunner);
    }

    /**
     * Generate the BuildConfig class for the project.
     * @param packageName the package in which to generate the class
     * @param debuggable whether the app is considered debuggable
     * @param javaLines additional java lines to put in the class. These must be valid Java lines.
     * @param sourceOutputDir directory where to put this. This is the source folder, not the
     *                        package folder.
     * @throws IOException
     */
    public void generateBuildConfig(
            @NonNull String packageName,
                     boolean debuggable,
            @NonNull List<String> javaLines,
            @NonNull String sourceOutputDir) throws IOException {

        BuildConfigGenerator generator = new BuildConfigGenerator(
                sourceOutputDir, packageName, debuggable);
        generator.generate(javaLines);
    }

    /**
     * Merges all the manifests into a single manifest
     *
     * @param mainManifest The main manifest of the application.
     * @param manifestOverlays manifest overlays coming from flavors and build types
     * @param libraries the library dependency graph
     * @param packageOverride a package name override. Can be null.
     * @param versionCode a version code to inject in the manifest or -1 to do nothing.
     * @param versionName a version name to inject in the manifest or null to do nothing.
     * @param minSdkVersion a minSdkVersion to inject in the manifest or -1 to do nothing.
     * @param targetSdkVersion a targetSdkVersion to inject in the manifest or -1 to do nothing.
     * @param outManifestLocation the output location for the merged manifest
     *
     * @see com.android.builder.VariantConfiguration#getMainManifest()
     * @see com.android.builder.VariantConfiguration#getManifestOverlays()
     * @see com.android.builder.VariantConfiguration#getDirectLibraries()
     * @see com.android.builder.VariantConfiguration#getMergedFlavor()
     * @see DefaultProductFlavor#getVersionCode()
     * @see DefaultProductFlavor#getVersionName()
     * @see DefaultProductFlavor#getMinSdkVersion()
     * @see DefaultProductFlavor#getTargetSdkVersion()
     */
    public void processManifest(
            @NonNull File mainManifest,
            @NonNull List<File> manifestOverlays,
            @NonNull List<? extends ManifestDependency> libraries,
                     String packageOverride,
                     int versionCode,
                     String versionName,
                     int minSdkVersion,
                     int targetSdkVersion,
            @NonNull String outManifestLocation) {
        checkNotNull(mainManifest, "mainManifest cannot be null.");
        checkNotNull(manifestOverlays, "manifestOverlays cannot be null.");
        checkNotNull(libraries, "libraries cannot be null.");
        checkNotNull(outManifestLocation, "outManifestLocation cannot be null.");

        try {
            Map<String, String> attributeInjection = getAttributeInjectionMap(
                    versionCode, versionName, minSdkVersion, targetSdkVersion);

            if (manifestOverlays.isEmpty() && libraries.isEmpty()) {
                // if no manifest to merge, just copy to location, unless we have to inject
                // attributes
                if (attributeInjection.isEmpty() && packageOverride == null) {
                    Files.copy(mainManifest, new File(outManifestLocation));
                } else {
                    ManifestMerger merger = new ManifestMerger(MergerLog.wrapSdkLog(mLogger), null);
                    doMerge(merger, new File(outManifestLocation), mainManifest,
                            attributeInjection, packageOverride);
                }
            } else {
                File outManifest = new File(outManifestLocation);

                // first merge the app manifest.
                if (!manifestOverlays.isEmpty()) {
                    File mainManifestOut = outManifest;

                    // if there is also libraries, put this in a temp file.
                    if (!libraries.isEmpty()) {
                        // TODO find better way of storing intermediary file?
                        mainManifestOut = File.createTempFile("manifestMerge", ".xml");
                        mainManifestOut.deleteOnExit();
                    }

                    ManifestMerger merger = new ManifestMerger(MergerLog.wrapSdkLog(mLogger), null);
                    doMerge(merger, mainManifestOut, mainManifest, manifestOverlays,
                            attributeInjection, packageOverride);

                    // now the main manifest is the newly merged one
                    mainManifest = mainManifestOut;
                    // and the attributes have been inject, no need to do it below
                    attributeInjection = null;
                }

                if (!libraries.isEmpty()) {
                    // recursively merge all manifests starting with the leaves and up toward the
                    // root (the app)
                    mergeLibraryManifests(mainManifest, libraries,
                            new File(outManifestLocation), attributeInjection, packageOverride);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Creates the manifest for a test variant
     *
     * @param testPackageName the package name of the test application
     * @param minSdkVersion the minSdkVersion of the test application
     * @param targetSdkVersion the targetSdkVersion of the test application
     * @param testedPackageName the package name of the tested application
     * @param instrumentationRunner the name of the instrumentation runner
     * @param libraries the library dependency graph
     * @param outManifestLocation the output location for the merged manifest
     *
     * @see com.android.builder.VariantConfiguration#getPackageName()
     * @see com.android.builder.VariantConfiguration#getTestedConfig()
     * @see com.android.builder.VariantConfiguration#getMinSdkVersion()
     * @see com.android.builder.VariantConfiguration#getTestedPackageName()
     * @see com.android.builder.VariantConfiguration#getInstrumentationRunner()
     * @see com.android.builder.VariantConfiguration#getDirectLibraries()
     */
    public void processTestManifest(
            @NonNull String testPackageName,
                     int minSdkVersion,
                     int targetSdkVersion,
            @NonNull String testedPackageName,
            @NonNull String instrumentationRunner,
            @NonNull List<? extends ManifestDependency> libraries,
            @NonNull String outManifestLocation) {
        checkNotNull(testPackageName, "testPackageName cannot be null.");
        checkNotNull(testedPackageName, "testedPackageName cannot be null.");
        checkNotNull(instrumentationRunner, "instrumentationRunner cannot be null.");
        checkNotNull(libraries, "libraries cannot be null.");
        checkNotNull(outManifestLocation, "outManifestLocation cannot be null.");

        if (!libraries.isEmpty()) {
            try {
                // create the test manifest, merge the libraries in it
                File generatedTestManifest = File.createTempFile("manifestMerge", ".xml");

                generateTestManifest(
                        testPackageName,
                        minSdkVersion,
                        targetSdkVersion,
                        testedPackageName,
                        instrumentationRunner,
                        generatedTestManifest.getAbsolutePath());

                mergeLibraryManifests(
                        generatedTestManifest,
                        libraries,
                        new File(outManifestLocation),
                        null, null);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else {
            generateTestManifest(
                    testPackageName,
                    minSdkVersion,
                    targetSdkVersion,
                    testedPackageName,
                    instrumentationRunner,
                    outManifestLocation);
        }
    }

    private void generateTestManifest(
            String testPackageName,
            int minSdkVersion,
            int targetSdkVersion,
            String testedPackageName,
            String instrumentationRunner,
            String outManifestLocation) {
        TestManifestGenerator generator = new TestManifestGenerator(
                outManifestLocation,
                testPackageName,
                minSdkVersion,
                targetSdkVersion,
                testedPackageName,
                instrumentationRunner);
        try {
            generator.generate();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @NonNull
    private Map<String, String> getAttributeInjectionMap(
                      int versionCode,
            @Nullable String versionName,
                      int minSdkVersion,
                      int targetSdkVersion) {

        Map<String, String> attributeInjection = Maps.newHashMap();

        if (versionCode != -1) {
            attributeInjection.put(
                    "/manifest|http://schemas.android.com/apk/res/android versionCode",
                    Integer.toString(versionCode));
        }

        if (versionName != null) {
            attributeInjection.put(
                    "/manifest|http://schemas.android.com/apk/res/android versionName",
                    versionName);
        }

        if (minSdkVersion != -1) {
            attributeInjection.put(
                    "/manifest/uses-sdk|http://schemas.android.com/apk/res/android minSdkVersion",
                    Integer.toString(minSdkVersion));
        }

        if (targetSdkVersion != -1) {
            attributeInjection.put(
                    "/manifest/uses-sdk|http://schemas.android.com/apk/res/android targetSdkVersion",
                    Integer.toString(targetSdkVersion));
        }
        return attributeInjection;
    }

    /**
     * Merges library manifests into a main manifest.
     * @param mainManifest the main manifest
     * @param directLibraries the libraries to merge
     * @param outManifest the output file
     * @throws IOException
     */
    private void mergeLibraryManifests(
            File mainManifest,
            Iterable<? extends ManifestDependency> directLibraries,
            File outManifest, Map<String, String> attributeInjection, String packageOverride)
            throws IOException {

        List<File> manifests = Lists.newArrayList();
        for (ManifestDependency library : directLibraries) {
            List<? extends ManifestDependency> subLibraries = library.getManifestDependencies();
            if (subLibraries.isEmpty()) {
                manifests.add(library.getManifest());
            } else {
                File mergeLibManifest = File.createTempFile("manifestMerge", ".xml");
                mergeLibManifest.deleteOnExit();

                // don't insert the attribute injection into libraries
                mergeLibraryManifests(
                        library.getManifest(), subLibraries, mergeLibManifest, null, null);

                manifests.add(mergeLibManifest);
            }
        }

        ManifestMerger merger = new ManifestMerger(MergerLog.wrapSdkLog(mLogger), null);
        doMerge(merger, outManifest, mainManifest, manifests, attributeInjection, packageOverride);
    }

    private void doMerge(ManifestMerger merger, File output, File input,
                               Map<String, String> injectionMap, String packageOverride) {
        List<File> list = Collections.emptyList();
        doMerge(merger, output, input, list, injectionMap, packageOverride);
    }

    private void doMerge(ManifestMerger merger, File output, File input, List<File> subManifests,
                               Map<String, String> injectionMap, String packageOverride) {
        if (!merger.process(output, input,
                subManifests.toArray(new File[subManifests.size()]),
                injectionMap, packageOverride)) {
            throw new RuntimeException("Manifest merging failed. See console for more info.");
        }
    }

    /**
     * Process the resources and generate R.java and/or the packaged resources.
     *
     * @param manifestFile the location of the manifest file
     * @param resFolder the merged res folder
     * @param assetsDir the merged asset folder
     * @param libraries the flat list of libraries
     * @param packageForR Package override to generate the R class in a different package.
     * @param sourceOutputDir optional source folder to generate R.java
     * @param resPackageOutput optional filepath for packaged resources
     * @param proguardOutput optional filepath for proguard file to generate
     * @param type the type of the variant being built
     * @param debuggable whether the app is debuggable
     * @param options the {@link com.android.builder.model.AaptOptions}
     *
     *
     * @throws IOException
     * @throws InterruptedException
     * @throws LoggedErrorException
     */
    public void processResources(
            @NonNull  File manifestFile,
            @NonNull  File resFolder,
            @Nullable File assetsDir,
            @NonNull  List<? extends SymbolFileProvider> libraries,
            @Nullable String packageForR,
            @Nullable String sourceOutputDir,
            @Nullable String symbolOutputDir,
            @Nullable String resPackageOutput,
            @Nullable String proguardOutput,
                      VariantConfiguration.Type type,
                      boolean debuggable,
            @NonNull AaptOptions options)
            throws IOException, InterruptedException, LoggedErrorException {

        checkNotNull(manifestFile, "manifestFile cannot be null.");
        checkNotNull(resFolder, "resFolder cannot be null.");
        checkNotNull(libraries, "libraries cannot be null.");
        checkNotNull(options, "options cannot be null.");
        // if both output types are empty, then there's nothing to do and this is an error
        checkArgument(sourceOutputDir != null || resPackageOutput != null,
                "No output provided for aapt task");

        // launch aapt: create the command line
        ArrayList<String> command = Lists.newArrayList();

        String aapt = mBuildTools.getPath(BuildToolInfo.PathId.AAPT);
        if (aapt == null || !new File(aapt).isFile()) {
            throw new IllegalStateException("aapt is missing");
        }

        command.add(aapt);
        command.add("package");

        if (mVerboseExec) {
            command.add("-v");
        }

        command.add("-f");

        command.add("--no-crunch");

        // inputs
        command.add("-I");
        command.add(mTarget.getPath(IAndroidTarget.ANDROID_JAR));

        command.add("-M");
        command.add(manifestFile.getAbsolutePath());

        if (resFolder.isDirectory()) {
            command.add("-S");
            command.add(resFolder.getAbsolutePath());
        }

        if (assetsDir != null && assetsDir.isDirectory()) {
            command.add("-A");
            command.add(assetsDir.getAbsolutePath());
        }

        // outputs

        if (sourceOutputDir != null) {
            command.add("-m");
            command.add("-J");
            command.add(sourceOutputDir);
        }

        if (type != VariantConfiguration.Type.LIBRARY && resPackageOutput != null) {
            command.add("-F");
            command.add(resPackageOutput);

            if (proguardOutput != null) {
                command.add("-G");
                command.add(proguardOutput);
            }
        }

        // options controlled by build variants

        if (debuggable) {
            command.add("--debug-mode");
        }

        if (type == VariantConfiguration.Type.DEFAULT) {
            if (packageForR != null) {
                command.add("--custom-package");
                command.add(packageForR);
                mLogger.verbose("Custom package for R class: '%s'", packageForR);
            }
        }

        // library specific options
        if (type == VariantConfiguration.Type.LIBRARY) {
            command.add("--non-constant-id");
        }

        // AAPT options
        String ignoreAssets = options.getIgnoreAssets();
        if (ignoreAssets != null) {
            command.add("--ignore-assets");
            command.add(ignoreAssets);
        }

        List<String> noCompressList = options.getNoCompress();
        if (noCompressList != null) {
            for (String noCompress : noCompressList) {
                command.add("-0");
                command.add(noCompress);
            }
        }

        if (symbolOutputDir != null &&
                (type == VariantConfiguration.Type.LIBRARY || !libraries.isEmpty())) {
            command.add("--output-text-symbols");
            command.add(symbolOutputDir);
        }

        mCmdLineRunner.runCmdLine(command);

        // now if the project has libraries, R needs to be created for each libraries,
        // but only if the current project is not a library.
        if (type != VariantConfiguration.Type.LIBRARY && !libraries.isEmpty()) {
            SymbolLoader fullSymbolValues = null;

            // First pass processing the libraries, collecting them by packageName,
            // and ignoring the ones that have the same package name as the application
            // (since that R class was already created).
            String appPackageName = packageForR;
            if (appPackageName == null) {
                appPackageName = VariantConfiguration.getManifestPackage(manifestFile);
            }

            // list of all the symbol loaders per package names.
            Multimap<String, SymbolLoader> libMap = ArrayListMultimap.create();

            for (SymbolFileProvider lib : libraries) {
                File rFile = lib.getSymbolFile();
                // if the library has no resource, this file won't exist.
                if (rFile.isFile()) {

                    String packageName = VariantConfiguration.getManifestPackage(lib.getManifest());
                    if (appPackageName.equals(packageName)) {
                        // ignore libraries that have the same package name as the app
                        continue;
                    }

                    // load the full values if that's not already been done.
                    // Doing it lazily allow us to support the case where there's no
                    // resources anywhere.
                    if (fullSymbolValues == null) {
                        fullSymbolValues = new SymbolLoader(new File(symbolOutputDir, "R.txt"),
                                mLogger);
                        fullSymbolValues.load();
                    }

                    SymbolLoader libSymbols = new SymbolLoader(rFile, mLogger);
                    libSymbols.load();


                    // store these symbols by associating them with the package name.
                    libMap.put(packageName, libSymbols);
                }
            }

            // now loop on all the package name, merge all the symbols to write, and write them
            for (String packageName : libMap.keySet()) {
                Collection<SymbolLoader> symbols = libMap.get(packageName);

                SymbolWriter writer = new SymbolWriter(sourceOutputDir, packageName,
                        fullSymbolValues);
                for (SymbolLoader symbolLoader : symbols) {
                    writer.addSymbolsToWrite(symbolLoader);
                }
                writer.write();
            }
        }
    }

    /**
     * Compiles all the aidl files found in the given source folders.
     *
     * @param sourceFolders all the source folders to find files to compile
     * @param sourceOutputDir the output dir in which to generate the source code
     * @param importFolders import folders
     * @param dependencyFileProcessor the dependencyFileProcessor to record the dependencies
     *                                of the compilation.
     * @throws IOException
     * @throws InterruptedException
     * @throws LoggedErrorException
     */
    public void compileAllAidlFiles(@NonNull List<File> sourceFolders,
                                    @NonNull File sourceOutputDir,
                                    @NonNull List<File> importFolders,
                                    @Nullable DependencyFileProcessor dependencyFileProcessor)
            throws IOException, InterruptedException, LoggedErrorException {
        checkNotNull(sourceFolders, "sourceFolders cannot be null.");
        checkNotNull(sourceOutputDir, "sourceOutputDir cannot be null.");
        checkNotNull(importFolders, "importFolders cannot be null.");

        String aidl = mBuildTools.getPath(BuildToolInfo.PathId.AIDL);
        if (aidl == null || !new File(aidl).isFile()) {
            throw new IllegalStateException("aidl is missing");
        }

        List<File> fullImportList = Lists.newArrayListWithCapacity(
                sourceFolders.size() + importFolders.size());
        fullImportList.addAll(sourceFolders);
        fullImportList.addAll(importFolders);

        AidlProcessor processor = new AidlProcessor(
                aidl,
                mTarget.getPath(IAndroidTarget.ANDROID_AIDL),
                fullImportList,
                sourceOutputDir,
                dependencyFileProcessor != null ?
                        dependencyFileProcessor : sNoOpDependencyFileProcessor,
                mCmdLineRunner);

        SourceSearcher searcher = new SourceSearcher(sourceFolders, "aidl");
        searcher.setUseExecutor(true);
        searcher.search(processor);
    }

    /**
     * Compiles the given aidl file.
     *
     * @param aidlFile the AIDL file to compile
     * @param sourceOutputDir the output dir in which to generate the source code
     * @param importFolders all the import folders, including the source folders.
     * @param dependencyFileProcessor the dependencyFileProcessor to record the dependencies
     *                                of the compilation.
     * @throws IOException
     * @throws InterruptedException
     * @throws LoggedErrorException
     */
    public void compileAidlFile(@NonNull File aidlFile,
                                @NonNull File sourceOutputDir,
                                @NonNull List<File> importFolders,
                                @Nullable DependencyFileProcessor dependencyFileProcessor)
            throws IOException, InterruptedException, LoggedErrorException {
        checkNotNull(aidlFile, "aidlFile cannot be null.");
        checkNotNull(sourceOutputDir, "sourceOutputDir cannot be null.");
        checkNotNull(importFolders, "importFolders cannot be null.");

        String aidl = mBuildTools.getPath(BuildToolInfo.PathId.AIDL);
        if (aidl == null || !new File(aidl).isFile()) {
            throw new IllegalStateException("aidl is missing");
        }

        AidlProcessor processor = new AidlProcessor(
                aidl,
                mTarget.getPath(IAndroidTarget.ANDROID_AIDL),
                importFolders,
                sourceOutputDir,
                dependencyFileProcessor != null ?
                        dependencyFileProcessor : sNoOpDependencyFileProcessor,
                mCmdLineRunner);

        processor.processFile(aidlFile);
    }

    /**
     * Compiles all the renderscript files found in the given source folders.
     *
     * Right now this is the only way to compile them as the renderscript compiler requires all
     * renderscript files to be passed for all compilation.
     *
     * Therefore whenever a renderscript file or header changes, all must be recompiled.
     *
     * @param sourceFolders all the source folders to find files to compile
     * @param importFolders all the import folders.
     * @param sourceOutputDir the output dir in which to generate the source code
     * @param resOutputDir the output dir in which to generate the bitcode file
     * @param targetApi the target api
     * @param debugBuild whether the build is debug
     * @param optimLevel the optimization level
     *
     * @throws IOException
     * @throws InterruptedException
     * @throws LoggedErrorException
     */
    public void compileAllRenderscriptFiles(@NonNull List<File> sourceFolders,
                                            @NonNull List<File> importFolders,
                                            @NonNull File sourceOutputDir,
                                            @NonNull File resOutputDir,
                                            int targetApi,
                                            boolean debugBuild,
                                            int optimLevel)
            throws IOException, InterruptedException, LoggedErrorException {
        checkNotNull(sourceFolders, "sourceFolders cannot be null.");
        checkNotNull(importFolders, "importFolders cannot be null.");
        checkNotNull(sourceOutputDir, "sourceOutputDir cannot be null.");
        checkNotNull(resOutputDir, "resOutputDir cannot be null.");

        String renderscript = mBuildTools.getPath(BuildToolInfo.PathId.LLVM_RS_CC);
        if (renderscript == null || !new File(renderscript).isFile()) {
            throw new IllegalStateException("llvm-rs-cc is missing");
        }

        // gather the files to compile
        FileGatherer fileGatherer = new FileGatherer();
        SourceSearcher searcher = new SourceSearcher(sourceFolders, "rs", "fs");
        searcher.setUseExecutor(false);
        searcher.search(fileGatherer);

        List<File> renderscriptFiles = fileGatherer.getFiles();

        if (renderscriptFiles.isEmpty()) {
            return;
        }

        String rsPath = mBuildTools.getPath(BuildToolInfo.PathId.ANDROID_RS);
        String rsClangPath = mBuildTools.getPath(BuildToolInfo.PathId.ANDROID_RS_CLANG);

        // the renderscript compiler doesn't expect the top res folder,
        // but the raw folder directly.
        File rawFolder = new File(resOutputDir, SdkConstants.FD_RES_RAW);

        // compile all the files in a single pass
        ArrayList<String> command = Lists.newArrayList();

        command.add(renderscript);

        if (debugBuild) {
            command.add("-g");
        }

        command.add("-O");
        command.add(Integer.toString(optimLevel));

        // add all import paths
        command.add("-I");
        command.add(rsPath);
        command.add("-I");
        command.add(rsClangPath);

        for (File importPath : importFolders) {
            if (importPath.isDirectory()) {
                command.add("-I");
                command.add(importPath.getAbsolutePath());
            }
        }

        // source output
        command.add("-p");
        command.add(sourceOutputDir.getAbsolutePath());

        // res output
        command.add("-o");
        command.add(rawFolder.getAbsolutePath());

        command.add("-target-api");
        command.add(Integer.toString(targetApi < 11 ? 11 : targetApi));

        // input files
        for (File sourceFile : renderscriptFiles) {
            command.add(sourceFile.getAbsolutePath());
        }

        mCmdLineRunner.runCmdLine(command);
    }

    /**
     * Computes and returns the leaf folders based on a given file extension.
     *
     * This looks through all the given root import folders, and recursively search for leaf
     * folders containing files matching the given extensions. All the leaf folders are gathered
     * and returned in the list.
     *
     * @param extension the extension to search for.
     * @param importFolders an array of list of root folders.
     * @return a list of leaf folder, never null.
     */
    @NonNull
    public List<File> getLeafFolders(@NonNull String extension, List<File>... importFolders) {
        List<File> results = Lists.newArrayList();

        if (importFolders != null) {
            for (List<File> folders : importFolders) {
                SourceSearcher searcher = new SourceSearcher(folders, extension);
                searcher.setUseExecutor(false);
                LeafFolderGatherer processor = new LeafFolderGatherer();
                try {
                    searcher.search(processor);
                } catch (InterruptedException e) {
                    // wont happen as we're not using the executor, and our processor
                    // doesn't throw those.
                } catch (IOException e) {
                    // wont happen as we're not using the executor, and our processor
                    // doesn't throw those.
                } catch (LoggedErrorException e) {
                    // wont happen as we're not using the executor, and our processor
                    // doesn't throw those.
                }

                results.addAll(processor.getFolders());
            }
        }

        return results;
    }

    /**
     * Converts the bytecode to Dalvik format
     * @param classesLocation the location of the compiler output
     * @param libraries the list of libraries
     * @param outDexFile the location of the output classes.dex file
     * @param dexOptions dex options
     * @param incremental true if it should attempt incremental dex if applicable
     *
     * @throws IOException
     * @throws InterruptedException
     * @throws LoggedErrorException
     */
    public void convertByteCode(
            @NonNull Iterable<File> classesLocation,
            @NonNull Iterable<File> libraries,
            @Nullable File proguardFile,
            @NonNull String outDexFile,
            @NonNull DexOptions dexOptions,
            boolean incremental) throws IOException, InterruptedException, LoggedErrorException {
        checkNotNull(classesLocation, "classesLocation cannot be null.");
        checkNotNull(libraries, "libraries cannot be null.");
        checkNotNull(outDexFile, "outDexFile cannot be null.");
        checkNotNull(dexOptions, "dexOptions cannot be null.");

        // launch dx: create the command line
        ArrayList<String> command = Lists.newArrayList();

        String dx = mBuildTools.getPath(BuildToolInfo.PathId.DX);
        if (dx == null || !new File(dx).isFile()) {
            throw new IllegalStateException("dx is missing");
        }

        command.add(dx);

        command.add("--dex");

        if (mVerboseExec) {
            command.add("--verbose");
        }

        if (dexOptions.isCoreLibrary()) {
            command.add("--core-library");
        }

        if (incremental) {
            command.add("--incremental");
            command.add("--no-strict");
        }

        command.add("--output");
        command.add(outDexFile);

        // clean up and add class inputs
        List<String> classesList = Lists.newArrayList();
        for (File f : classesLocation) {
            if (f != null && f.exists()) {
                classesList.add(f.getAbsolutePath());
            }
        }

        if (!classesList.isEmpty()) {
            mLogger.verbose("Dex class inputs: " + classesList);
            command.addAll(classesList);
        }

        // clean up and add library inputs.
        List<String> libraryList = Lists.newArrayList();
        for (File f : libraries) {
            if (f != null && f.exists()) {
                libraryList.add(f.getAbsolutePath());
            }
        }

        if (!libraryList.isEmpty()) {
            mLogger.verbose("Dex library inputs: " + libraryList);
            command.addAll(libraryList);
        }

        if (proguardFile != null && proguardFile.exists()) {
            mLogger.verbose("ProGuarded inputs " + proguardFile);
            command.add(proguardFile.getAbsolutePath());
        }

        mCmdLineRunner.runCmdLine(command);
    }

    /**
     * Packages the apk.
     *
     * @param androidResPkgLocation the location of the packaged resource file
     * @param classesDexLocation the location of the classes.dex file
     * @param packagedJars the jars that are packaged (libraries + jar dependencies)
     * @param javaResourcesLocation the processed Java resource folder
     * @param jniLibsLocation the location of the compiled JNI libraries
     * @param jniDebugBuild whether the app should include jni debug data
     * @param signingConfig the signing configuration
     * @param outApkLocation location of the APK.
     * @throws DuplicateFileException
     * @throws FileNotFoundException if the store location was not found
     * @throws KeytoolException
     * @throws PackagerException
     * @throws SigningException when the key cannot be read from the keystore
     *
     * @see com.android.builder.VariantConfiguration#getPackagedJars()
     */
    public void packageApk(
            @NonNull String androidResPkgLocation,
            @NonNull String classesDexLocation,
            @NonNull List<File> packagedJars,
            @Nullable String javaResourcesLocation,
            @Nullable String jniLibsLocation,
            boolean jniDebugBuild,
            @Nullable SigningConfig signingConfig,
            @NonNull String outApkLocation) throws DuplicateFileException, FileNotFoundException,
            KeytoolException, PackagerException, SigningException {
        checkNotNull(androidResPkgLocation, "androidResPkgLocation cannot be null.");
        checkNotNull(classesDexLocation, "classesDexLocation cannot be null.");
        checkNotNull(outApkLocation, "outApkLocation cannot be null.");

        CertificateInfo certificateInfo = null;
        if (signingConfig != null && signingConfig.isSigningReady()) {
            certificateInfo = KeystoreHelper.getCertificateInfo(signingConfig);
            if (certificateInfo == null) {
                throw new SigningException("Failed to read key from keystore");
            }
        }

        try {
            Packager packager = new Packager(
                    outApkLocation, androidResPkgLocation, classesDexLocation,
                    certificateInfo, mCreatedBy, mLogger);

            packager.setJniDebugMode(jniDebugBuild);

            // figure out conflicts!
            JavaResourceProcessor resProcessor = new JavaResourceProcessor(packager);

            if (javaResourcesLocation != null) {
                resProcessor.addSourceFolder(javaResourcesLocation);
            }

            // add the resources from the jar files.
            for (File jar : packagedJars) {
                packager.addResourcesFromJar(jar);
            }

            // also add resources from library projects and jars
            if (jniLibsLocation != null) {
                packager.addNativeLibraries(jniLibsLocation);
            }

            packager.sealApk();
        } catch (SealedPackageException e) {
            // shouldn't happen since we control the package from start to end.
            throw new RuntimeException(e);
        }
    }
}
