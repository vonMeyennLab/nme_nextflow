/*
 * Copyright 2020, Seqera Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package nextflow.plugin

import java.nio.file.Path
import java.nio.file.Paths

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.extension.Bolts
import nextflow.extension.FilesEx
import nextflow.util.CacheHelper
import org.pf4j.PluginManager
import org.pf4j.PluginStateEvent
import org.pf4j.PluginStateListener
import org.pf4j.update.UpdateManager
/**
 * Manage plugins installation and configuration
 * 
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@CompileStatic
class PluginsFacade implements PluginStateListener {

    private Path PLUGINS_LOCAL_ROOT = Paths.get('.nextflow/plr')

    private Map<String,String> env = new HashMap<>(System.getenv())

    private String mode
    private Path root
    private UpdateManager updater
    private PluginManager manager
    private DefaultPlugins defaultPlugins

    PluginsFacade() {
        mode = env.get('NXF_PLUGINS_MODE') ?: 'dev'
        root = Paths.get(env.get('NXF_PLUGINS_DIR') ?: 'plugins')
        System.setProperty('pf4j.mode', mode)
        defaultPlugins = new DefaultPlugins()
    }

    PluginsFacade(Path root, String mode='prod') {
        this.mode = mode
        this.root = root
        System.setProperty('pf4j.mode', mode)
        defaultPlugins = new DefaultPlugins()
    }

    protected void init(Path root, List<PluginSpec> specs) {
        this.manager = createManager(root, specs)
        this.updater = createUpdater(root, manager)
    }

    protected Path localRoot(List<PluginSpec> specs) {
        if( !specs )
            return null
        final unique = CacheHelper.hasher(specs).hash().toString()
        final localRoot = PLUGINS_LOCAL_ROOT.resolve(unique)
        log.debug "Plugins local root: $localRoot"
        FilesEx.mkdirs(localRoot)
        return localRoot
    }

    protected PluginManager createManager(Path root, List<PluginSpec> specs) {
        final result = mode!='dev' ? new LocalPluginManager( localRoot(specs) ) : new DevPluginManager(root)
        result.addPluginStateListener(this)
        return result
    }

    protected UpdateManager createUpdater(Path root, PluginManager manager) {
        return ( mode!='dev'
                ? new PluginUpdater(manager, root, new URL(Plugins.DEFAULT_PLUGINS_REPO))
                : new DevPluginUpdater(manager) )
    }

    @Override
    void pluginStateChanged(PluginStateEvent ev) {
        final err = ev.plugin.failedException
        final dsc = ev.plugin.descriptor
        if( err ) {
            throw new IllegalStateException("Unable to start plugin id=${dsc.pluginId} version=${dsc.version} -- cause: ${err.message ?: err}", err)
        }
    }

    PluginManager getManager() { manager }

    synchronized void setup(Map config = Collections.emptyMap()) {
        if( manager )
            throw new IllegalArgumentException("Plugin system was already setup")
        else {
            log.debug "Setting up plugin manager > NXF_PLUGINS_MODE=${mode}; NXF_PLUGINS_DIR=$root"
            // make sure plugins dir exists
            if( !FilesEx.mkdirs(root) )
                throw new IOException("Unable to create plugins dir: $root")
            final specs = pluginsRequirement(config)
            init(root, specs)
            manager.loadPlugins()
            start(specs)
        }
    }

    synchronized void stop() {
        if( manager ) {
            manager.stopPlugins()
            manager = null
        }
    }

    def <T> List<T> getExtensions(Class<T> type) {
        if( !manager )
            setup()
        manager.getExtensions(type)
    }


    void start( String pluginId ) {
         start( defaultPlugins.getPlugin(pluginId) )
    }

    void start(PluginSpec plugin) {
        final result = manager.getPlugin(plugin.id)
        if( !result ) {
            log.debug "Installing plugin $plugin"
            // install & start the plugin
            updater.installPlugin(plugin.id, plugin.version)
        }
        else if( result.descriptor.version != plugin.version ) {
            log.debug "Updating plugin $plugin"
            // update & start the plugin
            updater.updatePlugin(plugin.id, plugin.version)
        }
        else {
            log.debug "Starting plugin $plugin"
            manager.startPlugin(plugin.id)
        }
    }

    void start(List<PluginSpec> specs) {
        for( PluginSpec it : specs ) {
            start(it)
        }
    }

    protected List<PluginSpec> pluginsRequirement(Map config) {
        def specs = parseConf(config)
        if( env.get('NXF_PACK')=='all' ) {
            // custom plugins are not allowed for nextflow self-contained package
            if( specs )
                log.warn "Nextflow self-contained distribution only allows default plugins -- User config plugins will be ignored: ${specs.join(',')}"
            return Collections.emptyList()
        }
        if( specs ) {
            log.debug "Plugins declared=$specs"
        }
        else if( env.get('NXF_PLUGINS_DEFAULT')=='true' ){
            specs = defaultPluginsConf(config)
            log.debug "Plugins default=$specs"
        }

        // add tower plugin when config contains tower options
        if( config.containsKey('tower') && !specs.find {it.id == 'tower' } ) {
            specs << defaultPlugins.getPlugin('nf-tower')
        }

        return specs
    }

    protected List<PluginSpec> defaultPluginsConf(Map config) {
        final plugins = new ArrayList<PluginSpec>()
        final executor = Bolts.navigate(config, 'process.executor')

        if( executor == 'awsbatch' )
            plugins << defaultPlugins.getPlugin('nf-amazon')

        if( executor == 'google-lifesciences' )
            plugins << defaultPlugins.getPlugin('nf-google')

        if( executor == 'ignite' || System.getProperty('nxf.node.daemon')=='true') {
            plugins << defaultPlugins.getPlugin('nf-ignite')
            plugins << defaultPlugins.getPlugin('nf-amazon')
        }

        if( !plugins ) {
            // always include amazon plugin for backward compatibility
            plugins << defaultPlugins.getPlugin('nf-amazon')
        }

        return plugins
    }

    /**
     * Lookup for plugins declared in the nextflow.config using the `plugins` scope
     *
     * @param config The nextflow config as a Map object
     * @return The list of declared plugins
     */
    protected List<PluginSpec> parseConf(Map config) {
        final pluginsConf = config.plugins as List<String>
        final result = new ArrayList( pluginsConf?.size() ?: 0 )
        if(pluginsConf) for( String it : pluginsConf ) {
            result.add( PluginSpec.parse(it) )
        }
        return result
    }

}
