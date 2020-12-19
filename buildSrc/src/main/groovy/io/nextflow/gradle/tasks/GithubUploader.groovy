/*
 * Copyright 2020, Seqera Labs
 * Copyright 2013-2019, Centre for Genomic Regulation (CRG)
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

package io.nextflow.gradle.tasks

import groovy.transform.CompileStatic
import groovy.transform.Memoized
import io.nextflow.gradle.util.GithubClient
import org.apache.commons.codec.digest.DigestUtils
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
/**
 * Upload project artifact to the corresponding Github repository releases page
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@CompileStatic
class GithubUploader extends DefaultTask {

    /**
     * The source file to upload
     */
    @Input final ListProperty<String> assets = project.objects.listProperty(String)

    @Input final Property<String> repo = project.objects.property(String)

    @Input final Property<String> release = project.objects.property(String)

    @Input String owner

    @Input boolean overwrite = false

    @Input boolean dryRun = false

    @Input boolean skipExisting

    @Input boolean draft

    @Input String authToken

    @Input String userName

    @Memoized
    private GithubClient getClient() {
        new GithubClient(authToken: authToken, owner: owner, repo: repo.get(), userName: userName)
    }

    @TaskAction
    def task() {
        final files = (List) assets.get()
        for( String it : files ) {
            upload( new File(it) )
        }
    }

    private void upload(File sourceFile) {
        if( !sourceFile.exists() )
            throw new GradleException("Github upload failed -- Source file does not exists: $sourceFile")

        final fileName = sourceFile.name
        final asset = client.getReleaseAsset(release.get(), fileName)
        if ( asset ) {
            if( skipExisting && isSameContent(sourceFile, asset) ) {
                logger.quiet("${owner}/${repo}/${fileName} exists! -- Skipping it.")
            }
            else if (overwrite) {
                updateRelease(sourceFile)
            }
            else {
                throw new GradleException("${owner}/${repo.get()}/${fileName} exists! -- Refuse to owerwrite it.")
            }
        }
        else {
            uploadRelease(sourceFile)
        }
    }

    private void updateRelease(File sourceFile) {
        if( dryRun ) {
            logger.quiet("Would update ${sourceFile} → github.com://$owner/${repo.get()}")
        }
        else {
            logger.quiet("Updating ${sourceFile} → github.com://$owner/${repo.get()}")
            client.deleteReleaseAsset(release.get(), sourceFile.name)
            client.uploadReleaseAsset(release.get(), sourceFile, mime(sourceFile.name))
        }
    }

    private void uploadRelease(File sourceFile) {
        if( dryRun ) {
            logger.quiet("Would upload ${sourceFile} → github.com://$owner/${repo.get()}")
        }
        else {
            logger.quiet("Uploading ${sourceFile} → github.com://$owner/${repo.get()}")
            final rel = client.getRelease(release.get()) ?: client.createRelease(release.get())
            final releaseId = (rel.id as Long).toString()
            client.uploadReleaseAsset(releaseId, sourceFile, mime(sourceFile.name))
        }
    }

    private String mime(String fileName) {
        if( fileName.endsWith('.zip') ) {
            return "application/zip"
        }
        if( fileName.endsWith('.json') ) {
            return "application/json"
        }
        throw new IllegalArgumentException("Unknown file type: $fileName")
    }

    private boolean isSameContent(File sourceFile, InputStream asset ) {
        final d1 = sourceFile
                .withInputStream { InputStream it -> DigestUtils.sha512Hex(it) }
        final d2 = DigestUtils.sha512Hex(asset)
        return d1 == d2
    }
}
