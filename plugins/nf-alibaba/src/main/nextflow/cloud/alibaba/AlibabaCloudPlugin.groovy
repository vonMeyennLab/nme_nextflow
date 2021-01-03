package nextflow.cloud.alibaba

import groovy.transform.CompileStatic
import nextflow.plugin.BasePlugin
import org.pf4j.PluginWrapper
/**
 * Alibaba cloud plugin entry point
 * 
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@CompileStatic
class AlibabaCloudPlugin extends BasePlugin {

    AlibabaCloudPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

}
