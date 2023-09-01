package org.jenkinsci.plugins.github_branch_source;

import com.cloudbees.jenkins.GitHubRepositoryName;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.model.Item;
import hudson.scm.SCM;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMHeadEvent;
import jenkins.scm.api.SCMNavigator;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceOwner;
import jenkins.scm.api.trait.SCMHeadPrefilter;
import org.jenkinsci.plugins.github.extension.GHEventsSubscriber;
import org.jenkinsci.plugins.github.extension.GHSubscriberEvent;
import org.kohsuke.github.GHEventPayload;

public abstract class AbstractGHEventSubscriber extends GHEventsSubscriber {

    private static final Logger LOGGER = Logger.getLogger(AbstractGHEventSubscriber.class.getName());

    /**
     * Pattern to parse Github repository urls.
     */
    protected static final Pattern REPOSITORY_NAME_PATTERN = Pattern.compile("https?://([^/]+)/([^/]+)/([^/]+)");

    protected void onEvent(GHSubscriberEvent event) {
        super.onEvent(event);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean isApplicable(@Nullable Item project) {
        if (project == null) {
            return false;
        }
        if (project instanceof SCMSourceOwner) {
            SCMSourceOwner owner = (SCMSourceOwner) project;
            for (SCMSource source : owner.getSCMSources()) {
                if (source instanceof GitHubSCMSource) {
                    return true;
                }
            }
        }
        if (project.getParent() instanceof SCMSourceOwner) {
            SCMSourceOwner owner = (SCMSourceOwner) project.getParent();
            for (SCMSource source : owner.getSCMSources()) {
                if (source instanceof GitHubSCMSource) {
                    return true;
                }
            }
        }
        return false;
    }

    protected static <E extends SCMHeadEvent<?>> void fireAfterDelay(final E event) {
        SCMHeadEvent.fireLater(event, GitHubSCMSource.getEventDelaySeconds(), TimeUnit.SECONDS);
    }

    protected <P extends GHEventPayload> Optional<GitHubRepositoryName> validateRepository(P payload) {
        String repoUrl = payload.getRepository().getHtmlUrl().toExternalForm();
        Matcher matcher = REPOSITORY_NAME_PATTERN.matcher(repoUrl);
        if (!matcher.matches()) {
            LOGGER.log(Level.WARNING, "{0} does not match expected repository name pattern", repoUrl);
            return Optional.empty();
        }
        final GitHubRepositoryName changedRepository = GitHubRepositoryName.create(repoUrl);
        if (changedRepository == null) {
            LOGGER.log(Level.WARNING, "Malformed repository URL {0}", repoUrl);
            return Optional.empty();
        }
        return Optional.of(changedRepository);
    }

    protected abstract static class AbstractSCMHeadEvent<P extends GHEventPayload> extends SCMHeadEvent<P> {
        protected static final String R_HEADS = "refs/heads/";
        protected static final String R_TAGS = "refs/tags/";
        protected final String repoHost;
        protected final String repoOwner;
        protected final String repository;

        protected AbstractSCMHeadEvent(Type type, long timestamp, P payload, GitHubRepositoryName repo, String origin) {
            super(type, timestamp, payload, origin);
            this.repoHost = repo.getHost();
            this.repoOwner = payload.getRepository().getOwnerName();
            this.repository = payload.getRepository().getName();
        }

        protected boolean isApiMatch(String apiUri) {
            return repoHost.equalsIgnoreCase(RepositoryUriResolver.hostnameFromApiUri(apiUri));
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean isMatch(@NonNull SCMNavigator navigator) {
            return navigator instanceof GitHubSCMNavigator
                    && repoOwner.equalsIgnoreCase(((GitHubSCMNavigator) navigator).getRepoOwner());
        }

        /**
         * {@inheritDoc}
         */
        @NonNull
        @Override
        public String getSourceName() {
            return repository;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean isMatch(@NonNull SCM scm) {
            return false;
        }

        protected boolean isValidSource(@NonNull SCMSource source) {
            return source instanceof GitHubSCMSource
                    && isApiMatch(((GitHubSCMSource) source).getApiUri())
                    && repoOwner.equalsIgnoreCase(((GitHubSCMSource) source).getRepoOwner())
                    && repository.equalsIgnoreCase(((GitHubSCMSource) source).getRepository());
        }

        protected boolean isValidRepoName() {
            String repoName = getPayload().getRepository().getName();
            return repoName != null && repoName.matches(GitHubSCMSource.VALID_GITHUB_REPO_NAME);
        }

        protected static boolean isValidGitSha1(String value) {
            return value != null && value.matches(GitHubSCMSource.VALID_GIT_SHA1);
        }

        protected static boolean isValidUser(String value) {
            return value != null && value.matches(GitHubSCMSource.VALID_GITHUB_USER_NAME);
        }

        protected static BranchSCMHead branchSCMHeadOf(String ref) {
            if (ref.startsWith(R_HEADS)) {
                return new BranchSCMHead(ref.substring(R_HEADS.length()));
            }
            return new BranchSCMHead(ref);
        }

        protected static GitHubTagSCMHead tagSCMHeadOf(String ref, long timestamp) {
            if (ref.startsWith(R_TAGS)) {
                return new GitHubTagSCMHead(ref.substring(R_TAGS.length()), timestamp);
            }
            return new GitHubTagSCMHead(ref, timestamp);
        }

        protected static boolean atLeastOnePrefilterExcludesHead(
                @NonNull List<SCMHeadPrefilter> prefilters, @NonNull SCMSource source, @NonNull SCMHead head) {
            return prefilters.stream().anyMatch(prefilter -> prefilter.isExcluded(source, head));
        }

        protected static boolean isBranchRef(String refType) {
            return "branch".equals(refType);
        }

        protected static boolean isTagRef(String refType) {
            return "tag".equals(refType);
        }
    }

    public static class NoHashSCMRevision extends SCMRevision {

        public NoHashSCMRevision(@NonNull SCMHead head) {
            super(head);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            NoHashSCMRevision that = (NoHashSCMRevision) o;
            return Objects.equals(getHead(), that.getHead());
        }

        @Override
        public int hashCode() {
            return Objects.hash(getHead());
        }
    }
}
