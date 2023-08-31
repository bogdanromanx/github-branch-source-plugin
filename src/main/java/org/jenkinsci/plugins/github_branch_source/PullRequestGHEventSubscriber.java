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
import jenkins.plugins.git.AbstractGitSCMSource;
import jenkins.scm.api.SCMEvent;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMHeadObserver;
import jenkins.scm.api.SCMNavigator;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.mixin.ChangeRequestCheckoutStrategy;
import org.jenkinsci.plugins.github.extension.GHSubscriberEvent;
import org.kohsuke.github.GHEvent;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHub;

import java.io.IOException;
import java.io.StringReader;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static com.google.common.collect.Sets.immutableEnumSet;
import static org.kohsuke.github.GHEvent.PULL_REQUEST;

/**
 * This subscriber manages {@link org.kohsuke.github.GHEvent} PULL_REQUEST.
 */
@Extension
public class PullRequestGHEventSubscriber extends AbstractGHEventSubscriber {

    private static final Logger LOGGER = Logger.getLogger(PullRequestGHEventSubscriber.class.getName());

    /**
     * @return set with only PULL_REQUEST event
     */
    @Override
    protected Set<GHEvent> events() {
        return immutableEnumSet(PULL_REQUEST);
    }

    @Override
    protected void onEvent(GHSubscriberEvent event) {
        GHEventPayload.PullRequest p;
        try {
            p = GitHub.offline()
                    .parseEventPayload(new StringReader(event.getPayload()), GHEventPayload.PullRequest.class);
        } catch (IOException e) {
            LogRecord lr = new LogRecord(Level.WARNING, "Could not parse {0} event from {1} with payload: {2}");
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

        String action = p.getAction();
        if ("opened".equals(action)) {
            fireAfterDelay(new SCMHeadEventImpl(
                    SCMEvent.Type.CREATED, event.getTimestamp(), p, changedRepository, event.getOrigin()));
        } else if ("reopened".equals(action)
                || "synchronize".equals(action)
                || "edited".equals(action)
                || "ready_for_review".equals(action)
                || "converted_to_draft".equals(action)) {
            fireAfterDelay(new SCMHeadEventImpl(
                    SCMEvent.Type.UPDATED, event.getTimestamp(), p, changedRepository, event.getOrigin()));
        } else if ("closed".equals(action)) {
            fireAfterDelay(new SCMHeadEventImpl(
                    SCMEvent.Type.REMOVED, event.getTimestamp(), p, changedRepository, event.getOrigin()));
        }

    }

    private static class SCMHeadEventImpl extends AbstractSCMHeadEvent<GHEventPayload.PullRequest> {
        public SCMHeadEventImpl(
                Type type,
                long timestamp,
                GHEventPayload.PullRequest pullRequest,
                GitHubRepositoryName repo,
                String origin) {
            super(type, timestamp, pullRequest, repo, origin);
        }

        @Override
        public String descriptionFor(@NonNull SCMNavigator navigator) {
            String action = getPayload().getAction();
            if (action != null) {
                switch (action) {
                    case "opened":
                        return "Pull request #" + getPayload().getNumber() + " opened in repository " + repository;
                    case "reopened":
                        return "Pull request #" + getPayload().getNumber() + " reopened in repository " + repository;
                    case "synchronize":
                        return "Pull request #" + getPayload().getNumber() + " updated in repository " + repository;
                    case "closed":
                        return "Pull request #" + getPayload().getNumber() + " closed in repository " + repository;
                }
            }
            return "Pull request #" + getPayload().getNumber() + " event in repository " + repository;
        }

        @Override
        public String descriptionFor(SCMSource source) {
            String action = getPayload().getAction();
            if (action != null) {
                switch (action) {
                    case "opened":
                        return "Pull request #" + getPayload().getNumber() + " opened";
                    case "reopened":
                        return "Pull request #" + getPayload().getNumber() + " reopened";
                    case "synchronize":
                        return "Pull request #" + getPayload().getNumber() + " updated";
                    case "closed":
                        return "Pull request #" + getPayload().getNumber() + " closed";
                }
            }
            return "Pull request #" + getPayload().getNumber() + " event";
        }

        @Override
        public String description() {
            String action = getPayload().getAction();
            if (action != null) {
                switch (action) {
                    case "opened":
                        return "Pull request #"
                                + getPayload().getNumber()
                                + " opened in repository "
                                + repoOwner
                                + "/"
                                + repository;
                    case "reopened":
                        return "Pull request #"
                                + getPayload().getNumber()
                                + " reopened in repository "
                                + repoOwner
                                + "/"
                                + repository;
                    case "synchronize":
                        return "Pull request #"
                                + getPayload().getNumber()
                                + " updated in repository "
                                + repoOwner
                                + "/"
                                + repository;
                    case "closed":
                        return "Pull request #"
                                + getPayload().getNumber()
                                + " closed in repository "
                                + repoOwner
                                + "/"
                                + repository;
                }
            }
            return "Pull request #" + getPayload().getNumber() + " event in repository " + repoOwner + "/" + repository;
        }

        @NonNull
        @Override
        public Map<SCMHead, SCMRevision> heads(@NonNull SCMSource source) {
            if (!isValidSource(source)) {
                return Collections.emptyMap();
            }
            GitHubSCMSource src = (GitHubSCMSource) source;

            if (!isValidRepoName()) {
                // fake repository name
                return Collections.emptyMap();
            }

            GHEventPayload.PullRequest pullRequest = getPayload();
            GHPullRequest ghPullRequest = pullRequest.getPullRequest();
            GHUser user;
            try {
                user = ghPullRequest.getHead().getUser();
            } catch (IOException e) {
                // fake owner name
                return Collections.emptyMap();
            }

            String prOwnerName = user.getLogin();
            if (!isValidUser(prOwnerName)) {
                // fake owner name
                return Collections.emptyMap();
            }
            if (!isValidGitSha1(ghPullRequest.getBase().getSha())) {
                // fake base sha1
                return Collections.emptyMap();
            }
            if (!isValidGitSha1(ghPullRequest.getHead().getSha())) {
                // fake head sha1
                return Collections.emptyMap();
            }

            boolean fork = !src.getRepoOwner().equalsIgnoreCase(prOwnerName);

            Map<SCMHead, SCMRevision> result = new HashMap<>();
            GitHubSCMSourceContext context =
                    new GitHubSCMSourceContext(null, SCMHeadObserver.none()).withTraits(src.getTraits());
            if (!fork && context.wantBranches()) {
                final String branchName = ghPullRequest.getHead().getRef();
                SCMHead head = new BranchSCMHead(branchName);
                if (!atLeastOnePrefilterExcludesHead(context.prefilters(), source, head)) {
                    SCMRevision hash = new AbstractGitSCMSource.SCMRevisionImpl(head, ghPullRequest.getHead().getSha());
                    result.put(head, hash);
                }
            }
            if (context.wantPRs()) {
                int number = pullRequest.getNumber();
                Set<ChangeRequestCheckoutStrategy> strategies =
                        fork ? context.forkPRStrategies() : context.originPRStrategies();
                for (ChangeRequestCheckoutStrategy strategy : strategies) {
                    final String branchName;
                    if (strategies.size() == 1) {
                        branchName = "PR-" + number;
                    } else {
                        branchName = "PR-" + number + "-" + strategy.name().toLowerCase(Locale.ENGLISH);
                    }
                    PullRequestSCMHead head;
                    PullRequestSCMRevision revision;
                    switch (strategy) {
                        case MERGE:
                            // it will take a call to GitHub to get the merge commit, so let the event receiver
                            // poll
                            head = new PullRequestSCMHead(ghPullRequest, branchName, true);
                            revision = null;
                            break;
                        default:
                            // Give the event receiver the data we have so they can revalidate
                            head = new PullRequestSCMHead(ghPullRequest, branchName, false);
                            revision = new PullRequestSCMRevision(
                                    head,
                                    ghPullRequest.getBase().getSha(),
                                    ghPullRequest.getHead().getSha());
                            break;
                    }
                    if (!atLeastOnePrefilterExcludesHead(context.prefilters(), source, head)) {
                        result.put(head, revision);
                    }
                }
            }
            return result;
        }
    }
}
