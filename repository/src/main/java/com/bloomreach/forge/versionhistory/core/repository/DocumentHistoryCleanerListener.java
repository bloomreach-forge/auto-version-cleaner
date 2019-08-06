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
package com.bloomreach.forge.versionhistory.core.repository;

import java.util.Map;

import javax.jcr.Credentials;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.SimpleCredentials;

import org.hippoecm.repository.HippoStdNodeType;
import org.hippoecm.repository.api.HippoNodeType;
import org.onehippo.cms7.event.HippoEvent;
import org.onehippo.cms7.event.HippoEventConstants;
import org.onehippo.cms7.services.eventbus.Subscribe;
import org.onehippo.repository.events.HippoWorkflowEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bloomreach.forge.versionhistory.core.DocumentHistoryCleanerTask;

/**
 * EventBus event listener, which listens to document publication events and invokes {@link DocumentHistoryCleanerTask}
 * to clean up version history of the subject document.
 */
public class DocumentHistoryCleanerListener {

    private static Logger log = LoggerFactory.getLogger(DocumentHistoryCleanerListener.class);

    private static final Credentials SYSTEM_CREDENTIALS = new SimpleCredentials("system", new char[] {});

    private final Session daemonSession;
    private final DocumentHistoryCleanerConfiguration defaultConfig;
    private final Map<String, DocumentHistoryCleanerConfiguration> documentTypeConfigs;

    public DocumentHistoryCleanerListener(final Session daemonSession,
            final DocumentHistoryCleanerConfiguration defaultConfig,
            final Map<String, DocumentHistoryCleanerConfiguration> documentTypeConfigs) {
        this.daemonSession = daemonSession;
        this.defaultConfig = defaultConfig;
        this.documentTypeConfigs = documentTypeConfigs;
    }

    @Subscribe
    public void handleEvent(final HippoEvent event) {
        final String category = event.category();

        if (!HippoEventConstants.CATEGORY_WORKFLOW.equals(category)) {
            return;
        }

        final HippoWorkflowEvent<?> wfEvent = (HippoWorkflowEvent<?>) event;
        final String action = wfEvent.action();

        if (!"publish".equals(action)) {
            return;
        }

        final String documentType = wfEvent.documentType();
        final String subjectId = wfEvent.subjectId();
        final String subjectPath = wfEvent.subjectPath();

        final DocumentHistoryCleanerConfiguration docTypeConfig = documentTypeConfigs.get(documentType);
        final long maxDays = (docTypeConfig != null) ? docTypeConfig.getMaxDays() : defaultConfig.getMaxDays();
        final long maxRevisions = (docTypeConfig != null) ? docTypeConfig.getMaxRevisions()
                : defaultConfig.getMaxRevisions();

        Session session = null;

        try {
            session = daemonSession.impersonate(SYSTEM_CREDENTIALS);

            final Node subjectNode = session.getNodeByIdentifier(subjectId);
            final Node previewVariantNode = getPreviewVariantNode(subjectNode);

            final DocumentHistoryCleanerTask task = new DocumentHistoryCleanerTask(session, previewVariantNode);
            task.setMaxDays(maxDays);
            task.setMaxRevisions(maxRevisions);
            task.execute();
        } catch (Exception e) {
            log.error("Failed to clean revision history for the document () at {}.", subjectId, subjectPath, e);
        } finally {
            if (session != null) {
                session.logout();
            }
        }
    }

    private Node getPreviewVariantNode(final Node subjectNode) throws RepositoryException {
        if (isPreviewVariantNode(subjectNode)) {
            return subjectNode;
        }

        final Node handle = (subjectNode.isNodeType(HippoNodeType.NT_HANDLE)) ? subjectNode : subjectNode.getParent();

        for (NodeIterator nodeIt = handle.getNodes(handle.getName()); nodeIt.hasNext();) {
            final Node node = nodeIt.nextNode();

            if (node != null && isPreviewVariantNode(node)) {
                return node;
            }
        }

        return null;
    }

    private boolean isPreviewVariantNode(final Node node) throws RepositoryException {
        if (node.isNodeType(HippoStdNodeType.NT_PUBLISHABLE)) {
            final String hippoState = node.getProperty(HippoStdNodeType.HIPPOSTD_STATE).getString();

            if (HippoStdNodeType.UNPUBLISHED.equals(hippoState)) {
                return true;
            }
        }

        return false;
    }
}
