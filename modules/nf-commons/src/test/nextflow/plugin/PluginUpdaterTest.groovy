package nextflow.plugin

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

import org.pf4j.Plugin
import org.pf4j.PluginWrapper
import spock.lang.Specification
/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class PluginUpdaterTest extends Specification {

    static class FooPlugin extends Plugin {
        FooPlugin(PluginWrapper wrapper) {
            super(wrapper)
        }
    }


    def 'should install a plugin' () {
        given:
        def PLUGIN = 'my-plugin-1.0.0'
        def folder = Files.createTempDirectory('test')

        and:
        // the plugin to be installed
        Path plugin = folder.resolve('plugins')
        def plugin1 = createPlugin(plugin, 'my-plugin', '1.0.0', FooPlugin.class)
        def plugin2 = createPlugin(plugin, 'my-plugin', '2.0.0', FooPlugin.class)
        def zip1 = zipDir(plugin1)
        def zip2 = zipDir(plugin2)
        and:
        // this represents the remote repo from where plugins are downloaded
        def repoDir = Files.createDirectory(folder.resolve('repo'))
        createRepositoryIndex(repoDir, zip1, zip2)

        and:
        // the central cache where downloaded unzipped plugins are kept
        def cacheDir = Files.createDirectory(folder.resolve('cache'))
        // the app local dir
        def localDir =  Files.createDirectory(folder.resolve('local'))
        and:
        def manager = new LocalPluginManager(localDir)
        def updater = new PluginUpdater(manager, cacheDir, new URL("file:${repoDir.resolve('plugins.json')}"))

        when:
        updater.installPlugin( 'my-plugin', '1.0.0' )

        then:
        manager.getPlugin('my-plugin').plugin.class == FooPlugin.class
        manager.getPlugin('my-plugin').descriptor.getPluginId() == 'my-plugin'
        manager.getPlugin('my-plugin').descriptor.getVersion() == '1.0.0'
        and:
        cacheDir.resolve(PLUGIN).exists()
        cacheDir.resolve(PLUGIN).isDirectory()
        cacheDir.resolve(PLUGIN).resolve('MANIFEST.MF').isFile()
        and:
        localDir.resolve(PLUGIN).exists()
        localDir.resolve(PLUGIN).isLink()
        localDir.resolve(PLUGIN).resolve('MANIFEST.MF').text == cacheDir.resolve(PLUGIN).resolve('MANIFEST.MF').text

        cleanup:
        folder?.deleteDir()
    }


    def 'should update a plugin' () {
        given:
        def folder = Files.createTempDirectory('test')
        and:
        // the plugin to be installed
        def pluginDir = folder.resolve('plugins')
        def plugin1 = createPlugin(pluginDir, 'my-plugin', '1.0.0', FooPlugin.class)
        def plugin2 = createPlugin(pluginDir, 'my-plugin', '2.0.0', FooPlugin.class)
        def zip1 = zipDir(plugin1)
        def zip2 = zipDir(plugin2)
        and:
        // this represents the remote repo from where plugins are downloaded
        def repoDir = Files.createDirectory(folder.resolve('repo'))
        createRepositoryIndex(repoDir, zip1, zip2)
        and:
        // the central cache where downloaded unzipped plugins are kept
        def cacheDir = Files.createDirectory(folder.resolve('cache'))
        and:
        // the app local dir
        def localDir =  Files.createDirectory(folder.resolve('local'))
        and:
        def manager = new LocalPluginManager(localDir)
        def updater = new PluginUpdater(manager, cacheDir, new URL("file:${repoDir.resolve('plugins.json')}"))

        when:
        updater.installPlugin('my-plugin', '1.0.0')
        then:
        manager.getPlugin('my-plugin').plugin.class == FooPlugin.class
        manager.getPlugin('my-plugin').descriptor.getPluginId() == 'my-plugin'
        manager.getPlugin('my-plugin').descriptor.getVersion() == '1.0.0'
        and:
        cacheDir.resolve('my-plugin-1.0.0').exists()
        cacheDir.resolve('my-plugin-1.0.0').isDirectory()

        when:
        updater.updatePlugin( 'my-plugin', '2.0.0' )
        then:
        localDir.resolve('my-plugin-2.0.0').exists()
        !localDir.resolve('my-plugin-1.0.0').exists()
        and:
        cacheDir.resolve('my-plugin-1.0.0').exists()
        cacheDir.resolve('my-plugin-2.0.0').exists()
        and:
        manager.getPlugin('my-plugin').plugin.class == FooPlugin.class
        manager.getPlugin('my-plugin').descriptor.getPluginId() == 'my-plugin'
        manager.getPlugin('my-plugin').descriptor.getVersion() == '2.0.0'

        cleanup:
        folder?.deleteDir()
    }


    def 'should not download existing plugin' () {
        given:
        def folder = Files.createTempDirectory('test')
        and:
        // this represents the remote repo from where plugins are downloaded
        def repoDir = Files.createDirectory(folder.resolve('repo'))
        createEmptyIndex(repoDir)
        and:
        // the central cache where downloaded unzipped plugins are kept
        def cacheDir = Files.createDirectory(folder.resolve('cache'))
        def plugin1 = createPlugin(cacheDir,'my-plugin', '1.0.0', FooPlugin.class)
        def plugin2 = createPlugin(cacheDir,'my-plugin', '2.0.0', FooPlugin.class)
        and:
        // the app local dir
        def localDir =  Files.createDirectory(folder.resolve('local'))
        and:
        def manager = new LocalPluginManager(localDir)
        def updater = new PluginUpdater(manager, cacheDir, new URL("file:${repoDir.resolve('plugins.json')}"))

        when:
        updater.installPlugin('my-plugin', '1.0.0')
        then:
        manager.getPlugin('my-plugin').plugin.class == FooPlugin.class
        manager.getPlugin('my-plugin').descriptor.getPluginId() == 'my-plugin'
        manager.getPlugin('my-plugin').descriptor.getVersion() == '1.0.0'
        and:
        cacheDir.resolve('my-plugin-1.0.0').exists()
        cacheDir.resolve('my-plugin-1.0.0').isDirectory()
        and:
        localDir.resolve('my-plugin-1.0.0').exists()
        localDir.resolve('my-plugin-1.0.0').isLink()

        when:
        updater.updatePlugin( 'my-plugin', '2.0.0' )
        then:
        localDir.resolve('my-plugin-2.0.0').exists()
        !localDir.resolve('my-plugin-1.0.0').exists()
        and:
        cacheDir.resolve('my-plugin-1.0.0').exists()
        cacheDir.resolve('my-plugin-2.0.0').exists()
        and:
        manager.getPlugin('my-plugin').plugin.class == FooPlugin.class
        manager.getPlugin('my-plugin').descriptor.getPluginId() == 'my-plugin'
        manager.getPlugin('my-plugin').descriptor.getVersion() == '2.0.0'

        cleanup:
        folder?.deleteDir()
    }


    static private Path createPlugin(Path baseDir, String id, String ver, Class clazz) {
        def fqn = "$id-$ver".toString()
        def pluginDir = baseDir.resolve(fqn)
        pluginDir.mkdirs()

        pluginDir.resolve('file1.txt').text = 'foo'
        pluginDir.resolve('file2.txt').text = 'bar'
        pluginDir.resolve('MANIFEST.MF').text = """\
                Manifest-Version: 1.0
                Plugin-Class: ${clazz.getName()}
                Plugin-Id: $id
                Plugin-Version: $ver
                """
                .stripIndent()

        return pluginDir
    }

    static private void createRepositoryIndex(Path repoDir, Path zip1, Path zip2) {
        repoDir.resolve('plugins.json').text = """
              [{
                "id": "my-plugin",
                "description": "Test plugin",
                "releases": [
                  {
                    "version": "1.0.0",
                    "date": "Jun 25, 2020 9:58:35 PM",
                    "url": "file:${zip1}"
                  },
                  {
                    "version": "2.0.0",
                    "date": "Jun 25, 2020 9:58:35 PM",
                    "url": "file:${zip2}"
                  }
                ]
              }]
            """
    }

    static private void createEmptyIndex(Path repoDir) {
        repoDir.resolve('plugins.json').text = """
              [{
                "id": "my-plugin",
                "description": "Test plugin",
                "releases": [ ]
              }]
            """
    }

    static private Path zipDir(final Path folder) throws IOException {

        def zipFilePath = folder.resolveSibling( "${folder.name}.zip" )

        try (
                FileOutputStream fos = new FileOutputStream(zipFilePath.toFile());
                ZipOutputStream zos = new ZipOutputStream(fos)
        ) {
            Files.walkFileTree(folder, new SimpleFileVisitor<Path>() {
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    zos.putNextEntry(new ZipEntry(folder.relativize(file).toString()));
                    Files.copy(file, zos);
                    zos.closeEntry();
                    return FileVisitResult.CONTINUE;
                }

                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    zos.putNextEntry(new ZipEntry(folder.relativize(dir).toString() + "/"));
                    zos.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        return zipFilePath
    }

}