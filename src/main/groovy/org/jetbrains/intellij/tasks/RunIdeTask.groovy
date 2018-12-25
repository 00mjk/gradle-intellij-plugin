package org.jetbrains.intellij.tasks

import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.*
import org.gradle.internal.jvm.Jvm
import org.gradle.internal.os.OperatingSystem
import org.gradle.util.CollectionUtils
import org.jetbrains.intellij.Utils

import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry

class RunIdeTask extends JavaExec {
    private static final def PREFIXES = [IU: null,
                                         IC: 'Idea',
                                         RM: 'Ruby',
                                         PY: 'Python',
                                         PC: 'PyCharmCore',
                                         PE: 'PyCharmEdu',
                                         PS: 'PhpStorm',
                                         WS: 'WebStorm',
                                         OC: 'AppCode',
                                         CL: 'CLion',
                                         DB: '0xDBE',
                                         AI: 'AndroidStudio',
                                         GO: 'GoLand',
                                         RD: 'Rider',
                                         RS: 'Rider']

    private List<Object> requiredPluginIds = []
    private Object ideaDirectory
    private Object configDirectory
    private Object systemDirectory
    private Object pluginsDirectory
    private Object jbreVersion
    private boolean shortenClasspathWithManifestJar

    List<String> getRequiredPluginIds() {
        CollectionUtils.stringize(requiredPluginIds.collect {
            it instanceof Closure ? (it as Closure).call() : it
        }.flatten())
    }

    void setRequiredPluginIds(Object... requiredPluginIds) {
        this.requiredPluginIds.clear()
        this.requiredPluginIds.addAll(requiredPluginIds as List)
    }

    void requiredPluginIds(Object... requiredPluginIds) {
        this.requiredPluginIds.addAll(requiredPluginIds as List)
    }

    boolean getShortenClasspathWithManifestJar() {
        shortenClasspathWithManifestJar
    }

    void setShortenClasspathWithManifestJar(Object shortenClasspathWithManifestJar) {
        this.shortenClasspathWithManifestJar = shortenClasspathWithManifestJar
    }

    void shortenClasspathWithManifestJar(Object shortenClasspathWithManifestJar) {
        this.jbreVersion = jbreVersion
    }

    @Input
    @Optional
    String getJbreVersion() {
        Utils.stringInput(jbreVersion)
    }

    void setJbreVersion(Object jbreVersion) {
        this.jbreVersion = jbreVersion
    }

    void jbreVersion(Object jbreVersion) {
        this.jbreVersion = jbreVersion
    }

    @InputDirectory
    File getIdeaDirectory() {
        ideaDirectory != null ? project.file(ideaDirectory) : null
    }

    void setIdeaDirectory(Object ideaDirectory) {
        this.ideaDirectory = ideaDirectory
    }

    void ideaDirectory(Object ideaDirectory) {
        this.ideaDirectory = ideaDirectory
    }

    @OutputDirectory
    File getConfigDirectory() {
        configDirectory != null ? project.file(configDirectory) : null
    }

    void setConfigDirectory(Object configDirectory) {
        this.configDirectory = configDirectory
    }

    void configDirectory(Object configDirectory) {
        this.configDirectory = configDirectory
    }

    @OutputDirectory
    File getSystemDirectory() {
        systemDirectory != null ? project.file(systemDirectory) : null
    }

    void setSystemDirectory(Object systemDirectory) {
        this.systemDirectory = systemDirectory
    }

    void systemDirectory(Object systemDirectory) {
        this.systemDirectory = systemDirectory
    }

    File getPluginsDirectory() {
        pluginsDirectory != null ? project.file(pluginsDirectory) : null
    }

    void setPluginsDirectory(Object pluginsDirectory) {
        this.pluginsDirectory = pluginsDirectory
    }

    void pluginsDirectory(Object pluginsDirectory) {
        this.pluginsDirectory = pluginsDirectory
    }

    RunIdeTask() {
        setMain("com.intellij.idea.Main")
        enableAssertions = true
        outputs.upToDateWhen { false }
    }

    @Override
    void exec() {
        workingDir = project.file("${getIdeaDirectory()}/bin/")
        configureClasspath()
        configureSystemProperties()
        configureJvmArgs()
        executable(getExecutable())
        super.exec()
    }

    private void configureClasspath() {
        File ideaDirectory = getIdeaDirectory()
        def executable = getExecutable()
        def toolsJar = executable ? project.file(Utils.resolveToolsJar(executable)) : null
        toolsJar = toolsJar?.exists() ? toolsJar : Jvm.current().toolsJar
        if (toolsJar != null) {
            classpath += project.files(toolsJar)
        }
        classpath += project.files("$ideaDirectory/lib/idea_rt.jar",
                "$ideaDirectory/lib/idea.jar",
                "$ideaDirectory/lib/bootstrap.jar",
                "$ideaDirectory/lib/extensions.jar",
                "$ideaDirectory/lib/util.jar",
                "$ideaDirectory/lib/openapi.jar",
                "$ideaDirectory/lib/trove4j.jar",
                "$ideaDirectory/lib/jdom.jar",
                "$ideaDirectory/lib/log4j.jar")

        if (getShortenClasspathWithManifestJar()) {
            classpath = project.files(createManifestJar(classpath))
        }
    }

    def configureSystemProperties() {
        systemProperties(getSystemProperties())
        systemProperties(Utils.getIdeaSystemProperties(getConfigDirectory(), getSystemDirectory(), getPluginsDirectory(), getRequiredPluginIds()))
        def operatingSystem = OperatingSystem.current()
        def userDefinedSystemProperties = getSystemProperties()
        if (operatingSystem.isMacOsX()) {
            systemPropertyIfNotDefined("idea.smooth.progress", false, userDefinedSystemProperties)
            systemPropertyIfNotDefined("apple.laf.useScreenMenuBar", true, userDefinedSystemProperties)
            systemPropertyIfNotDefined("apple.awt.fileDialogForDirectories", true, userDefinedSystemProperties)
        } else if (operatingSystem.isUnix()) {
            systemPropertyIfNotDefined("sun.awt.disablegrab", true, userDefinedSystemProperties)
        }
        systemPropertyIfNotDefined("idea.classpath.index.enabled", false, userDefinedSystemProperties)
        systemPropertyIfNotDefined("idea.is.internal", true, userDefinedSystemProperties)

        if (!getSystemProperties().containsKey('idea.platform.prefix')) {
            def matcher = Utils.VERSION_PATTERN.matcher(Utils.ideaBuildNumber(getIdeaDirectory()))
            if (matcher.find()) {
                def abbreviation = matcher.group(1)
                def prefix = PREFIXES.get(abbreviation)
                if (prefix) {
                    systemProperty('idea.platform.prefix', prefix)

                    if (abbreviation == 'RD') {
                        // Allow debugging Rider's out of process ReSharper host
                        systemPropertyIfNotDefined('rider.debug.mono.debug', true, userDefinedSystemProperties)
                        systemPropertyIfNotDefined('rider.debug.mono.allowConnect', true, userDefinedSystemProperties)
                    }
                }
            }
        }
    }

    private void systemPropertyIfNotDefined(String name, Object value, Map<String, Object> userDefinedSystemProperties) {
        if (!userDefinedSystemProperties.containsKey(name)) {
            systemProperty(name, value)
        }
    }

    def configureJvmArgs() {
        jvmArgs = Utils.getIdeaJvmArgs(this, getJvmArgs(), getIdeaDirectory())
    }

    private File createManifestJar(FileCollection classpath) {
        def file = new File(getTemporaryDir(), "manifest.jar")
        def manifest = new Manifest()
        def attributes = manifest.getMainAttributes()
        attributes.put(Attributes.Name.MANIFEST_VERSION, '1.0')
        attributes.put(Attributes.Name.CLASS_PATH, classpath.files.collect { it.toURI().toString() }.join(' '))
        def outputStream = null
        try {
            outputStream = new JarOutputStream(new FileOutputStream(file), manifest)
            outputStream.putNextEntry(new ZipEntry("META-INF/"))
            return file
        }
        finally {
            outputStream?.close()
        }
    }
}
