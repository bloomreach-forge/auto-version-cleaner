/*
 *  Copyright 2019 BloomReach, Inc. (https://www.bloomreach.com)
 * 
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 * 
 *       http://www.apache.org/licenses/LICENSE-2.0
 * 
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.bloomreach.forge.versionhistory.core;

import java.util.Calendar;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.version.Version;
import javax.jcr.version.VersionHistory;
import javax.jcr.version.VersionIterator;
import javax.jcr.version.VersionManager;

import org.apache.commons.lang.time.DateFormatUtils;

/**
 * Document version history cleaner task.
 */
public class DocumentHistoryCleanerTask extends AbstractContentHistoryTask {

    /**
     * A day in milliseconds.
     */
    private static final long DAY_IN_MILLIS = 24L * 60L * 60L * 1000L;

    /**
     * Max revisions to keep in the version history.
     * If this is set to a negative integer, then this option will be ignored.
     */
    private long maxRevisions = -1L;

    /**
     * Max days to keep in the version history since the version created time.
     * If this is set to a negative integer, then this option will be ignored.
     */
    private long maxDays = -1L;

    /**
     * The versionable document variant node. i.e. the preview variant node which keeps the JCR version history.
     */
    private final Node documentNode;

    public DocumentHistoryCleanerTask(final Session session, final Node documentNode) throws RepositoryException {
        super(session);

        if (documentNode == null) {
            throw new IllegalArgumentException("document node must be not null.");
        }

        if (!documentNode.isNodeType("mix:versionable")) {
            throw new IllegalArgumentException("document node must be of type, mix:versionable.");
        }

        if (!documentNode.getPath().startsWith("/content/")) {
            throw new IllegalArgumentException("document node must be under /content/.");
        }

        if (documentNode.getPrimaryNodeType().getName().startsWith("hst:")
                || documentNode.getPath().startsWith("/hippo:configuration/")
                || documentNode.getPath().equals("/hippo:namespaces")
                || documentNode.getPath().startsWith("/hippo:namespaces/")) {
            throw new IllegalArgumentException("Not a document node, but a configuration node.");
        }

        this.documentNode = documentNode;
    }

    public long getMaxRevisions() {
        return maxRevisions;
    }

    public void setMaxRevisions(long maxRevisions) {
        this.maxRevisions = maxRevisions;
    }

    public long getMaxDays() {
        return maxDays;
    }

    public void setMaxDays(long maxDays) {
        this.maxDays = maxDays;
    }

    @Override
    protected void doExecute() throws RepositoryException {
        if (maxDays < 0L && maxRevisions < 0L) {
            return;
        }

        // gather versions
        final List<Version> versions = new LinkedList<>();
        final VersionManager versionManager = getSession().getWorkspace().getVersionManager();
        final VersionHistory versionHistory = versionManager.getVersionHistory(documentNode.getPath());

        for (VersionIterator versionIt = versionHistory.getAllVersions(); versionIt.hasNext();) {
            final Version version = versionIt.nextVersion();

            if (version == null) {
                continue;
            }

            final String[] labels = versionHistory.getVersionLabels(version);
            final boolean revisionVariant = (labels.length > 0) ? true : false;

            if (!version.getName().equals("jcr:rootVersion") && !revisionVariant) {
                versions.add(version);
            }
        }

        if (maxDays >= 0L) {
            final long nowInMillis = System.currentTimeMillis();
            final long maxDaysInMillis = maxDays * DAY_IN_MILLIS;

            for (Iterator<Version> versionIt = versions.iterator(); versionIt.hasNext();) {
                final Version version = versionIt.next();
                final Calendar created = version.getCreated();

                if (nowInMillis - created.getTimeInMillis() > maxDaysInMillis) {
                    getLogger().info("Removing old version, '{}' created on {} at {}, of document node at {}: {}",
                            version.getName(), DateFormatUtils.ISO_DATETIME_TIME_ZONE_FORMAT.format(created),
                            version.getPath(), documentNode.getPath(), version.getName());
                    versionHistory.removeVersion(version.getName());
                    versionIt.remove();
                }
            }
        }

        if (maxRevisions >= 0L) {
            final long revisionCount = versions.size();
            final long removeCount = revisionCount - maxRevisions;

            for (long l = 0; l < removeCount; l++) {
                final Version version = versions.remove(0);
                final Calendar created = version.getCreated();
                getLogger().info("Removing surplus version, '{}' created on {} at {}, of document node at {}: {}",
                        version.getName(), DateFormatUtils.ISO_DATETIME_TIME_ZONE_FORMAT.format(created),
                        version.getPath(), documentNode.getPath(), version.getName());
                versionHistory.removeVersion(version.getName());
            }
        }
    }
}
