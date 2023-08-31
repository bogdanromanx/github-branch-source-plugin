/*
 * The MIT License
 *
 * Copyright 2016 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.github_branch_source;

import com.cloudbees.jenkins.GitHubRepositoryName;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import jenkins.plugins.git.GitBranchSCMHead;
import jenkins.scm.api.SCMEvent;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMHeadObserver;
import jenkins.scm.api.SCMNavigator;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMSource;
import org.jenkinsci.plugins.github.extension.GHSubscriberEvent;
import org.kohsuke.github.GHEvent;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GitHub;

import java.io.StringReader;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static com.google.common.collect.Sets.immutableEnumSet;
import static org.kohsuke.github.GHEvent.CREATE;
import static org.kohsuke.github.GHEvent.DELETE;

/**
 * This subscriber manages {@link GHEvent} DELETE.
 */
@Extension
public class DeleteGHEventSubscriber extends AbstractGHEventSubscriber {

    private static final Logger LOGGER = Logger.getLogger(DeleteGHEventSubscriber.class.getName());

    /**
     * {@inheritDoc}
     *
     * @return set with only PULL_REQUEST event
     */
    @Override
    protected Set<GHEvent> events() {
        return immutableEnumSet(DELETE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onEvent(GHSubscriberEvent event) {
        GHEventPayload.Delete p;

        try {
            p = GitHub.offline()
                    .parseEventPayload(new StringReader(event.getPayload()), GHEventPayload.Delete.class);
        } catch (Exception e) {
            LogRecord lr =
                    new LogRecord(Level.WARNING, "Could not parse {0} event from {1} with payload: {2}");
            lr.setParameters(new Object[]{event.getGHEvent(), event.getOrigin(), event.getPayload()});
            lr.setThrown(e);
            LOGGER.log(lr);
            return;
        }

        String repoUrl = p.getRepository().getHtmlUrl().toExternalForm();
        LOGGER.log(Level.FINE, "Received {0} for {1} from {2}", new Object[]{
                event.getGHEvent(), repoUrl, event.getOrigin()
        });

        Optional<GitHubRepositoryName> repoNameOption = validateRepository(p);
        if (repoNameOption.isEmpty()) {
            return;
        }
        GitHubRepositoryName changedRepository = repoNameOption.get();

        fireAfterDelay(
                new SCMHeadEventImpl(
                        SCMEvent.Type.REMOVED,
                        event.getTimestamp(),
                        p,
                        changedRepository,
                        event.getOrigin()));
    }

    private static class SCMHeadEventImpl extends AbstractSCMHeadEvent<GHEventPayload.Delete> {
        public SCMHeadEventImpl(
                Type type,
                long timestamp,
                GHEventPayload.Delete delete,
                GitHubRepositoryName repo,
                String origin) {
            super(type, timestamp, delete, repo, origin);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String descriptionFor(@NonNull SCMNavigator navigator) {
            String ref = getPayload().getRef();
            if (ref.startsWith(R_TAGS)) {
                ref = ref.substring(R_TAGS.length());
                return "Delete event for tag " + ref + " in repository " + repository;
            }
            if (ref.startsWith(R_HEADS)) {
                ref = ref.substring(R_HEADS.length());
            }
            return "Delete event to branch " + ref + " in repository " + repository;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String descriptionFor(SCMSource source) {
            String ref = getPayload().getRef();
            if (ref.startsWith(R_TAGS)) {
                ref = ref.substring(R_TAGS.length());
                return "Delete event for tag " + ref;
            }
            if (ref.startsWith(R_HEADS)) {
                ref = ref.substring(R_HEADS.length());
            }
            return "Delete event to branch " + ref;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String description() {
            String ref = getPayload().getRef();
            if (ref.startsWith(R_TAGS)) {
                ref = ref.substring(R_TAGS.length());
                return "Delete event for tag " + ref + " in repository " + repoOwner + "/" + repository;
            }
            if (ref.startsWith(R_HEADS)) {
                ref = ref.substring(R_HEADS.length());
            }
            return "Delete event to branch " + ref + " in repository " + repoOwner + "/" + repository;
        }

        /**
         * {@inheritDoc}
         */
        @NonNull
        @Override
        public Map<SCMHead, SCMRevision> heads(@NonNull SCMSource source) {
            if (!isValidSource(source)) {
                return Collections.emptyMap();
            }
            GitHubSCMSource src = (GitHubSCMSource) source;
            GHEventPayload.Delete delete = getPayload();

            if (!isValidRepoName()) {
                // fake repository name
                return Collections.emptyMap();
            }

            if (!isValidUser(delete.getRepository().getOwnerName())) {
                // fake owner name
                return Collections.emptyMap();
            }

            String ref = delete.getRef();

            GitHubSCMSourceContext context =
                    new GitHubSCMSourceContext(null, SCMHeadObserver.none()).withTraits(src.getTraits());

            if (context.wantBranches() && !ref.startsWith(R_TAGS)) {
                // we only want the branch details if the branch is actually built!
                GitBranchSCMHead head = branchSCMHeadOf(ref);
                if (atLeastOnePrefilterExcludesHead(context.prefilters(), source, head)) {
                    return Collections.emptyMap();
                }

                return Collections.singletonMap(head, new NoHashSCMRevision(head));
            }

            if (context.wantTags() && ref.startsWith(R_TAGS)) {
                GitHubTagSCMHead head = new GitHubTagSCMHead(ref.substring(R_TAGS.length()), getTimestamp());

                if (atLeastOnePrefilterExcludesHead(context.prefilters(), source, head)) {
                    return Collections.emptyMap();
                }
                return Collections.singletonMap(head, new NoHashSCMRevision(head));
            }

            return Collections.emptyMap();
        }
    }
}
