/*
 * Copyright 2021, Microsoft Corp
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
 */

package nextflow.cloud.azure.config


import java.util.regex.Matcher
import java.util.regex.Pattern

import groovy.transform.CompileStatic
import nextflow.cloud.CloudTransferOptions
import nextflow.util.Duration
/**
 * Model Azure Batch pool config settings
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@CompileStatic
class AzBatchOpts implements CloudTransferOptions {

    static final private Pattern ENDPOINT_PATTERN = ~/https:\/\/(\w+)\.(\w+)\.batch\.azure\.com/

    int maxParallelTransfers
    int maxTransferAttempts
    Duration delayBetweenAttempts

    String accountName
    String accountKey
    String endpoint
    String location
    Boolean autoPoolMode
    Boolean allowPoolCreation
    Boolean deleteJobsOnCompletion
    Boolean deletePoolsOnCompletion

    Map<String,AzPoolOpts> pools

    AzBatchOpts(Map config) {
        assert config!=null
        accountName = config.accountName
        accountKey = config.accountKey
        endpoint = config.endpoint
        location = config.location
        autoPoolMode = config.autoPoolMode
        allowPoolCreation = config.allowPoolCreation
        deleteJobsOnCompletion = config.deleteJobsOnCompletion
        deletePoolsOnCompletion = config.deletePoolsOnCompletion
        pools = parsePools(config.pools instanceof Map ? config.pools as Map<String,Map> : Collections.<String,Map>emptyMap())
        maxParallelTransfers = config.maxParallelTransfers ? config.maxParallelTransfers as int : MAX_TRANSFER
        maxTransferAttempts = config.maxTransferAttempts ? config.maxTransferAttempts as int : MAX_TRANSFER_ATTEMPTS
        delayBetweenAttempts = config.delayBetweenAttempts ? config.delayBetweenAttempts as Duration : DEFAULT_DELAY_BETWEEN_ATTEMPTS
    }

    static Map<String,AzPoolOpts> parsePools(Map<String,Map> pools) {
        final result = new LinkedHashMap<String,AzPoolOpts>()
        for( Map.Entry<String,Map> entry : pools ) {
            result[entry.key] = new AzPoolOpts( entry.value )
        }
        if( !result.keySet().contains('auto') )
            result.put('auto', new AzPoolOpts())
        return result
    }

    AzPoolOpts pool(String name) {
        return pools.get(name)
    }

    AzPoolOpts autoPoolOpts() {
        pool('auto')
    }

    String toString() {
        "endpoint=$endpoint; account-name=$accountName; account-key=${accountKey?.redact()}"
    }

    private List<String> endpointParts() {
        // try to infer the account name from the endpoint
        Matcher m
        if( endpoint && (m = ENDPOINT_PATTERN.matcher(endpoint)).matches() ) {
            return [ m.group(1), m.group(2) ]
        }
        else {
            return Collections.emptyList()
        }
    }

    String getAccountName() {
        if( accountName )
            return accountName
        return endpointParts()[0]
    }

    String getLocation() {
        if( location )
            return location
        // try to infer the location name from the endpoint
        return endpointParts()[1]
    }

    String getEndpoint() {
        if( endpoint )
            return endpoint
        if( accountName && location )
            return "https://${accountName}.${location}.batch.azure.com"
        return null
    }

    boolean canCreatePool() {
        allowPoolCreation || autoPoolMode
    }
}
