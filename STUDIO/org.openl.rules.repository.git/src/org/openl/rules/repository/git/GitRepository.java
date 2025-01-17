package org.openl.rules.repository.git;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.jgit.api.*;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.RefNotAdvertisedException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.TrackingRefUpdate;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.eclipse.jgit.util.FS;
import org.openl.rules.repository.RRepositoryFactory;
import org.openl.rules.repository.api.*;
import org.openl.rules.repository.common.ChangesMonitor;
import org.openl.rules.repository.common.RevisionGetter;
import org.openl.rules.repository.exceptions.RRepositoryException;
import org.openl.util.FileUtils;
import org.openl.util.IOUtils;
import org.openl.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GitRepository implements FolderRepository, BranchRepository, Closeable, RRepositoryFactory {
    static final String DELETED_MARKER_FILE = ".archived";

    private final Logger log = LoggerFactory.getLogger(GitRepository.class);

    private String uri;
    private String login;
    private String password;
    private String userDisplayName;
    private String userEmail;
    private String localRepositoryPath;
    private String branch = Constants.MASTER;
    private String baseBranch = branch;
    private String tagPrefix = StringUtils.EMPTY;
    private int listenerTimerPeriod = 10;
    private int connectionTimeout = 60;
    private String commentTemplate;
    private String escapedCommentTemplate;
    private String gitSettingsPath;

    private ChangesMonitor monitor;
    private Git git;
    private NotResettableCredentialsProvider credentialsProvider;

    private ReadWriteLock repositoryLock = new ReentrantReadWriteLock();
    private ReentrantLock remoteRepoLock = new ReentrantLock();

    private Map<String, List<String>> branches = new HashMap<>();

    /**
     * Holds secondary repositories for other branches.
     */
    private Map<String, GitRepository> branchRepos = new HashMap<>();

    @Override
    public List<FileData> list(String path) throws IOException {
        return iterate(path, new ListCommand(resolveBranchId()));
    }

    @Override
    public FileData check(String name) throws IOException {
        return iterate(name, new CheckCommand());
    }

    @Override
    public FileItem read(String name) throws IOException {
        return iterate(name, new ReadCommand());
    }

    @Override
    @SuppressWarnings("squid:S2095") // resources are closed by IOUtils
    public FileData save(FileData data, InputStream stream) throws IOException {
        Lock writeLock = repositoryLock.writeLock();
        try {
            log.debug("save(data, stream): lock");
            writeLock.lock();

            reset();
            saveSingleFile(data, stream);
        } catch (IOException e) {
            reset();
            throw e;
        } catch (Exception e) {
            reset();
            throw new IOException(e.getMessage(), e);
        } finally {
            writeLock.unlock();
            log.debug("save(data, stream): unlock");
        }

        monitor.fireOnChange();

        return check(data.getName());
    }

    @Override
    public List<FileData> save(List<FileItem> fileItems) throws IOException {
        List<FileData> result = new ArrayList<>();
        Lock writeLock = repositoryLock.writeLock();
        String firstCommitId = null;
        try {
            log.debug("save(multipleFiles): lock");
            writeLock.lock();
            reset();
            git.checkout().setName(branch).call();
            for (FileItem fileItem : fileItems) {
                RevCommit commit = createCommit(fileItem.getData(), fileItem.getStream());
                if (firstCommitId == null) {
                    firstCommitId = commit.getId().getName();
                }

                resolveAndMerge(fileItem.getData(), false, commit);
                addTagToCommit(commit);
            }
            push();
        } catch (IOException e) {
            reset(firstCommitId);
            throw e;
        } catch (Exception e) {
            reset(firstCommitId);
            throw new IOException(e.getMessage(), e);
        } finally {
            writeLock.unlock();
            log.debug("save(multipleFiles): unlock");
        }
        monitor.fireOnChange();

        for (FileItem fileItem : fileItems) {
            result.add(check(fileItem.getData().getName()));
        }
        return result;
    }

    private void saveSingleFile(FileData data, InputStream stream) throws IOException {
        String commitId = null;
        try {
            String parentVersion = data.getVersion();
            boolean checkoutOldVersion = isCheckoutOldVersion(data.getName(), parentVersion);
            git.checkout().setName(checkoutOldVersion ? parentVersion : branch).call();
            RevCommit commit = createCommit(data, stream);
            commitId = commit.getId().getName();

            resolveAndMerge(data, checkoutOldVersion, commit);
            addTagToCommit(commit);

            push();
        } catch (IOException e) {
            reset(commitId);
            throw e;
        } catch (Exception e) {
            reset(commitId);
            throw new IOException(e.getMessage(), e);
        }
    }

    private RevCommit createCommit(FileData data, InputStream stream) throws GitAPIException, IOException {
        String fileInRepository = data.getName();

        File file = new File(localRepositoryPath, fileInRepository);
        createParent(file);
        IOUtils.copyAndClose(stream, new FileOutputStream(file));

        git.add().addFilepattern(fileInRepository).call();
        return git.commit()
            .setMessage(formatComment(CommitType.SAVE, data))
            .setCommitter(userDisplayName != null ? userDisplayName : data.getAuthor(),
                userEmail != null ? userEmail : "")
            .setOnly(fileInRepository)
            .call();
    }

    @Override
    public boolean delete(FileData data) {
        boolean deleted;
        String commitId = null;

        Lock writeLock = repositoryLock.writeLock();
        try {
            log.debug("delete(): lock");
            writeLock.lock();

            reset();
            git.checkout().setName(branch).call();

            String name = data.getName();
            File file = new File(localRepositoryPath, name);
            if (!file.exists()) {
                return false;
            }

            if (file.isDirectory()) {
                String commitMessage = formatComment(CommitType.ARCHIVE, data);

                // Create marker file if it absents and write current time
                try (DataOutputStream os = new DataOutputStream(
                    new FileOutputStream(new File(file, DELETED_MARKER_FILE)))) {
                    os.writeLong(System.currentTimeMillis());
                }

                String markerFile = name + "/" + DELETED_MARKER_FILE;
                git.add().addFilepattern(markerFile).call();
                RevCommit commit = git.commit()
                    .setMessage(commitMessage)
                    .setCommitter(userDisplayName != null ? userDisplayName : data.getAuthor(),
                        userEmail != null ? userEmail : "")
                    .setOnly(markerFile)
                    .call();
                commitId = commit.getId().getName();

                addTagToCommit(commit);
            } else {
                // Files can't be archived. Only folders.
                git.rm().addFilepattern(name).call();
                RevCommit commit = git.commit()
                    .setMessage(formatComment(CommitType.ERASE, data))
                    .setCommitter(userDisplayName != null ? userDisplayName : data.getAuthor(),
                        userEmail != null ? userEmail : "")
                    .call();
                commitId = commit.getId().getName();

                addTagToCommit(commit);
            }

            push();

            deleted = true;
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            reset(commitId);
            deleted = false;
        } finally {
            writeLock.unlock();
            log.debug("delete(): unlock");
        }

        monitor.fireOnChange();
        return deleted;
    }

    @SuppressWarnings("squid:S2095") // resources are closed by IOUtils
    private FileData copy(String srcName, FileData destData) throws IOException {
        String commitId = null;

        Lock writeLock = repositoryLock.writeLock();
        try {
            log.debug("copy(): lock");
            writeLock.lock();

            reset();
            git.checkout().setName(branch).call();

            File src = new File(localRepositoryPath, srcName);
            File dest = new File(localRepositoryPath, destData.getName());
            IOUtils.copyAndClose(new FileInputStream(src), new FileOutputStream(dest));

            git.add().addFilepattern(destData.getName()).call();
            RevCommit commit = git.commit()
                .setMessage(formatComment(CommitType.SAVE, destData))
                .setCommitter(userDisplayName != null ? userDisplayName : destData.getAuthor(),
                    userEmail != null ? userEmail : "")
                .call();
            commitId = commit.getId().getName();

            addTagToCommit(commit);

            push();
        } catch (IOException e) {
            reset(commitId);
            throw e;
        } catch (Exception e) {
            reset(commitId);
            throw new IOException(e);
        } finally {
            writeLock.unlock();
            log.debug("copy(): unlock");
        }

        monitor.fireOnChange();

        return check(destData.getName());
    }

    @Override
    public void setListener(Listener callback) {
        if (monitor != null) {
            monitor.setListener(callback);
        }
    }

    @Override
    public List<FileData> listHistory(String name) throws IOException {
        return iterateHistory(name, new ListHistoryVisitor());
    }

    @Override
    public List<FileData> listFiles(String path, String version) throws IOException {
        return parseHistory(path, version, new ListFilesHistoryVisitor(version));
    }

    @Override
    public FileData checkHistory(String name, String version) throws IOException {
        return parseHistory(name, version, new CheckHistoryVisitor(version));
    }

    @Override
    public FileItem readHistory(String name, String version) throws IOException {
        return parseHistory(name, version, new ReadHistoryVisitor(version));
    }

    @Override
    public boolean deleteHistory(FileData data) {
        String name = data.getName();
        String version = data.getVersion();
        String author = StringUtils.trimToEmpty(data.getAuthor());
        boolean deleted;
        String commitId = null;

        Lock writeLock = repositoryLock.writeLock();
        try {
            log.debug("deleteHistory(): lock");
            writeLock.lock();

            reset();
            git.checkout().setName(branch).call();

            RevCommit commit;
            if (version == null) {
                git.rm().addFilepattern(name).call();
                String commitMessage = formatComment(CommitType.ERASE, data);
                commit = git.commit()
                    .setCommitter(userDisplayName != null ? userDisplayName : author,
                        userEmail != null ? userEmail : "")
                    .setMessage(commitMessage)
                    .setOnly(name)
                    .call();
                commitId = commit.getId().getName();

                addTagToCommit(commit);
            } else {
                FileData fileData = checkHistory(name, version);
                if (fileData == null) {
                    return false;
                }

                if (!fileData.isDeleted()) {
                    // We can "delete" only archived versions. Other version can't be deleted.
                    return false;
                }

                String markerFile = name + "/" + DELETED_MARKER_FILE;
                git.rm().addFilepattern(markerFile).call();
                String commitMessage = formatComment(CommitType.RESTORE, data);
                commit = git.commit()
                    .setCommitter(userDisplayName != null ? userDisplayName : author,
                        userEmail != null ? userEmail : "")
                    .setMessage(commitMessage)
                    .setOnly(markerFile)
                    .call();
                commitId = commit.getId().getName();

                addTagToCommit(commit);
            }

            push();
            deleted = true;
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            deleted = false;
            reset(commitId);
        } finally {
            writeLock.unlock();
            log.debug("deleteHistory(): unlock");
        }

        monitor.fireOnChange();
        return deleted;
    }

    @Override
    public FileData copyHistory(String srcName, FileData destData, String version) throws IOException {
        if (version == null) {
            return copy(srcName, destData);
        }

        Lock writeLock = repositoryLock.writeLock();
        try {
            log.debug("copyHistory(): lock");
            writeLock.lock();

            reset();
            git.checkout().setName(branch).call();

            File src = new File(localRepositoryPath, srcName);
            if (src.isDirectory()) {
                List<FileItem> files = new ArrayList<>();
                try {
                    List<FileData> fileData = listFiles(srcName + "/", version);
                    for (FileData data : fileData) {
                        String fileFrom = data.getName();
                        FileItem fileItem = readHistory(fileFrom, data.getVersion());
                        String fileTo = destData.getName() + fileFrom.substring(srcName.length());
                        files.add(new FileItem(fileTo, fileItem.getStream()));
                    }
                    saveMultipleFiles(destData, files, ChangesetType.FULL);
                } finally {
                    for (FileItem file : files) {
                        IOUtils.closeQuietly(file.getStream());
                    }
                }
            } else {
                FileItem fileItem = null;
                try {
                    fileItem = readHistory(srcName, version);

                    destData.setSize(fileItem.getData().getSize());

                    saveSingleFile(destData, fileItem.getStream());
                } finally {
                    if (fileItem != null) {
                        IOUtils.closeQuietly(fileItem.getStream());
                    }
                }
            }
        } catch (IOException e) {
            reset();
            throw e;
        } catch (Exception e) {
            reset();
            throw new IOException(e);
        } finally {
            writeLock.unlock();
            log.debug("copyHistory(): unlock");
        }

        monitor.fireOnChange();
        return check(destData.getName());
    }

    @Override
    public void initialize() throws RRepositoryException {
        Lock writeLock = repositoryLock.writeLock();
        try {
            log.debug("initialize(): lock");
            writeLock.lock();

            if (StringUtils.isNotBlank(login) && StringUtils.isNotBlank(password)) {
                credentialsProvider = new NotResettableCredentialsProvider(login, password);
            }

            File local = new File(localRepositoryPath);

            boolean shouldClone;
            if (!local.exists()) {
                shouldClone = true;
            } else {
                File[] files = local.listFiles();
                if (files == null) {
                    throw new IOException("Folder '" + local + "' is not directory");
                }

                if (files.length > 0) {
                    if (RepositoryCache.FileKey.resolve(local, FS.DETECTED) != null) {
                        log.debug("Reuse existing local repository {}", local);
                        try (Repository repository = Git.open(local).getRepository()) {
                            String remoteUrl = repository.getConfig()
                                .getString(ConfigConstants.CONFIG_REMOTE_SECTION,
                                    Constants.DEFAULT_REMOTE_NAME,
                                    ConfigConstants.CONFIG_KEY_URL);
                            if (!uri.equals(remoteUrl)) {
                                URI proposedUri = getUri(uri);
                                URI savedUri = getUri(remoteUrl);
                                if (!proposedUri.equals(savedUri)) {
                                    throw new IOException(
                                        "Folder '" + local + "' already contains local git repository but is configured for different URI (" + remoteUrl + ").\nDelete it or choose another local path or set correct URL for repository.");
                                }
                            }
                        }
                        shouldClone = false;
                    } else {
                        // Can't overwrite existing files that is definitely not git repository
                        throw new IOException(
                            "Folder '" + local + "' already exists and is not empty. Delete it or choose another local path.");
                    }
                } else {
                    shouldClone = true;
                }
            }

            if (shouldClone) {
                try {
                    CloneCommand cloneCommand = Git.cloneRepository()
                        .setURI(uri)
                        .setDirectory(local)
                        .setBranch(branch)
                        .setCloneAllBranches(true);

                    CredentialsProvider credentialsProvider = getCredentialsProvider();
                    if (credentialsProvider != null) {
                        cloneCommand.setCredentialsProvider(credentialsProvider);
                    }

                    Git cloned = cloneCommand.call();
                    cloned.close();
                } catch (Exception e) {
                    FileUtils.deleteQuietly(local);
                    throw e;
                }
            }

            git = Git.open(local);
            StoredConfig config = git.getRepository().getConfig();
            if (StringUtils.isNotBlank(userDisplayName)) {
                config.setString(ConfigConstants.CONFIG_USER_SECTION,
                    null,
                    ConfigConstants.CONFIG_KEY_NAME,
                    userDisplayName);
            } else {
                config.unset(ConfigConstants.CONFIG_USER_SECTION, null, ConfigConstants.CONFIG_KEY_NAME);
            }
            if (StringUtils.isNotBlank(userEmail)) {
                config
                    .setString(ConfigConstants.CONFIG_USER_SECTION, null, ConfigConstants.CONFIG_KEY_EMAIL, userEmail);
            } else {
                config.unset(ConfigConstants.CONFIG_USER_SECTION, null, ConfigConstants.CONFIG_KEY_EMAIL);
            }
            config.save();

            // Track all remote branches as local branches
            List<Ref> remoteBranches = git.branchList().setListMode(ListBranchCommand.ListMode.REMOTE).call();
            TreeSet<String> localBranches = getAvailableBranches();
            String remotePrefix = Constants.R_REMOTES + Constants.DEFAULT_REMOTE_NAME + "/";
            for (Ref remoteBranch : remoteBranches) {
                if (!remoteBranch.getName().startsWith(remotePrefix)) {
                    log.warn("The branch {} will not be tracked", remoteBranch.getName());
                    continue;
                }
                String branchName = remoteBranch.getName().substring(remotePrefix.length());
                if (!localBranches.contains(branchName)) {
                    createRemoteTrackingBranch(branchName);
                }
            }

            if (!shouldClone) {
                try {
                    doFastForward(fetchAll());
                } catch (Exception e) {
                    log.warn(e.getMessage(), e);
                    if (credentialsProvider != null && credentialsProvider.isHasAuthorizationFailure()) {
                        throw new IOException("Incorrect login or password for git repository.");
                    }
                }

                boolean branchAbsents = git.getRepository().findRef(branch) == null;
                if (branchAbsents) {
                    createRemoteTrackingBranch(branch);
                }
            }

            readBranches();

            monitor = new ChangesMonitor(new GitRevisionGetter(), listenerTimerPeriod);

            for (String branch : getAvailableBranches()) {
                branchRepos.put(branch, createRepository(branch));
            }
        } catch (Exception e) {
            Throwable cause = ExceptionUtils.getRootCause(e);
            if (cause == null) {
                cause = e;
            }

            // Unknown host
            if (cause instanceof UnknownHostException) {
                String error = "Invalid URL " + uri;
                throw new RRepositoryException(error, new IllegalArgumentException(error));
            }

            // Other cases
            throw new RRepositoryException(e.getMessage(), e);
        } finally {
            writeLock.unlock();
            log.debug("initialize(): unlock");
        }
    }

    @Override
    public void close() {
        if (monitor != null) {
            monitor.release();
            monitor = null;
        }
        if (git != null) {
            git.close();
            git = null;
        }
        for (GitRepository repository : branchRepos.values()) {
            repository.close();
        }
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public void setLogin(String login) {
        this.login = login;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public void setUserDisplayName(String userDisplayName) {
        this.userDisplayName = userDisplayName;
    }

    public void setUserEmail(String userEmail) {
        this.userEmail = userEmail;
    }

    public void setLocalRepositoryPath(String localRepositoryPath) {
        this.localRepositoryPath = localRepositoryPath;
    }

    public void setBranch(String branch) {
        this.branch = StringUtils.isBlank(branch) ? Constants.MASTER : branch;
        this.baseBranch = this.branch;
    }

    public void setTagPrefix(String tagPrefix) {
        this.tagPrefix = StringUtils.trimToEmpty(tagPrefix);
    }

    public void setListenerTimerPeriod(int listenerTimerPeriod) {
        this.listenerTimerPeriod = listenerTimerPeriod;
    }

    public void setConnectionTimeout(int connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }

    public void setCommentTemplate(String commentTemplate) {
        this.commentTemplate = commentTemplate;
        String ct = commentTemplate.replaceAll("\\{commit-type}", "{0}")
            .replaceAll("\\{user-message}", "{1}")
            .replaceAll("\\{username}", "{2}");
        this.escapedCommentTemplate = escapeCurlyBrackets(ct);
    }

    public void setGitSettingsPath(String gitSettingsPath) {
        this.gitSettingsPath = gitSettingsPath;
    }

    private static TreeWalk buildTreeWalk(org.eclipse.jgit.lib.Repository repository,
            String path,
            RevTree tree) throws IOException {
        TreeWalk treeWalk;
        if (StringUtils.isEmpty(path)) {
            treeWalk = new TreeWalk(repository);
            treeWalk.addTree(tree);
            treeWalk.setRecursive(true);
            treeWalk.setPostOrderTraversal(false);
        } else {
            treeWalk = TreeWalk.forPath(repository, path, tree);
        }

        if (treeWalk == null) {
            throw new FileNotFoundException(
                "Did not find expected path '" + path + "' in tree '" + tree.getName() + "'");
        }
        return treeWalk;
    }

    private FileData createFileData(TreeWalk dirWalk, String baseFolder, ObjectId start) {
        String fullPath = baseFolder + dirWalk.getPathString();
        return new LazyFileData(branch,
            fullPath,
            new File(localRepositoryPath),
            start,
            getFileId(dirWalk),
            escapedCommentTemplate);
    }

    private ObjectId resolveBranchId() throws IOException {
        ObjectId branchId = git.getRepository().resolve(branch);
        if (branchId == null) {
            throw new IOException("Can't find branch '" + branch + "'");
        }
        return branchId;
    }

    private FileData createFileData(TreeWalk dirWalk, RevCommit fileCommit) {
        String fullPath = dirWalk.getPathString();

        return new LazyFileData(branch,
            fullPath,
            new File(localRepositoryPath),
            fileCommit,
            getFileId(dirWalk),
            escapedCommentTemplate);
    }

    private ObjectId getFileId(TreeWalk dirWalk) {
        int fileModeBits = dirWalk.getFileMode().getBits();
        ObjectId fileId = null;
        if ((fileModeBits & FileMode.TYPE_FILE) != 0) {
            fileId = dirWalk.getObjectId(0);
        }
        return fileId;
    }

    ObjectId getLastRevision() throws GitAPIException, IOException {
        FetchResult fetchResult;

        Lock readLock = repositoryLock.readLock();
        try {
            readLock.lock();

            boolean remoteLocked = remoteRepoLock.tryLock();
            if (!remoteLocked) {
                // Skip because is already fetching by other thread.
                return null;
            }
            try {
                fetchResult = fetchAll();
            } finally {
                remoteRepoLock.unlock();
            }
        } finally {
            readLock.unlock();
        }

        Lock writeLock = repositoryLock.writeLock();
        try {
            log.debug("pull(): lock");
            writeLock.lock();

            doFastForward(fetchResult);

            TreeSet<String> availableBranches = getAvailableBranches();
            for (List<String> projectBranches : branches.values()) {
                projectBranches.removeIf(branch -> !availableBranches.contains(branch));
            }
            saveBranches();

            reset();
        } finally {
            writeLock.unlock();
            log.debug("pull(): unlock");
        }

        try {
            log.debug("getLastRevision(): lock");
            readLock.lock();
            return git.getRepository().resolve("HEAD^{tree}");
        } finally {
            readLock.unlock();
            log.debug("getLastRevision(): unlock");
        }
    }

    private void doFastForward(FetchResult fetchResult) throws GitAPIException, IOException {
        for (TrackingRefUpdate refUpdate : fetchResult.getTrackingRefUpdates()) {
            RefUpdate.Result result = refUpdate.getResult();
            switch (result) {
                case FAST_FORWARD:
                    git.checkout().setName(refUpdate.getRemoteName()).call();

                    // It's assumed that we don't have unpushed commits at this point so there must be no additional
                    // merge
                    // while checking last revision. Accept only fast forwards.
                    git.merge()
                        .include(refUpdate.getNewObjectId())
                        .setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
                        .call();
                    break;
                case REJECTED_CURRENT_BRANCH:
                    git.checkout().setName(baseBranch).call(); // On the next fetch the branch probably will be deleted
                    break;
                case FORCED:
                    if (ObjectId.zeroId().equals(refUpdate.getNewObjectId())) {
                        String remoteName = refUpdate.getRemoteName();

                        if (remoteName.startsWith(Constants.R_HEADS)) {
                            // Delete the branch
                            String branchToDelete = Repository.shortenRefName(remoteName);
                            String currentBranch = Repository.shortenRefName(git.getRepository().getFullBranch());
                            if (branchToDelete.equals(currentBranch)) {
                                String branchToCheckout = baseBranch;
                                if (branchToCheckout.equals(branchToDelete)) {
                                    branchToCheckout = Constants.MASTER;
                                }
                                if (getAvailableBranches().contains(branchToCheckout)) {
                                    git.checkout().setName(branchToCheckout).call();
                                } else {
                                    git.checkout()
                                        .setName(branchToCheckout)
                                        .setCreateBranch(true)
                                        .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                                        .setStartPoint(Constants.DEFAULT_REMOTE_NAME + "/" + branchToCheckout)
                                        .call();
                                }
                            }
                            git.branchDelete().setBranchNames(branchToDelete).setForce(true).call();
                        }
                    }
                    break;
                case NEW:
                    if (ObjectId.zeroId().equals(refUpdate.getOldObjectId())) {
                        String remoteName = refUpdate.getRemoteName();
                        if (remoteName.startsWith(Constants.R_HEADS)) {
                            createRemoteTrackingBranch(Repository.shortenRefName(remoteName));
                        }
                    }
                    break;
                case NO_CHANGE:
                    // Do nothing
                    break;
                default:
                    log.warn("Unsupported type of fetch result type: {}", result);
                    break;
            }
        }
    }

    private void pull() throws GitAPIException, IOException {
        FetchResult fetchResult;
        try {
            remoteRepoLock.lock();
            fetchResult = fetchAll();
        } finally {
            remoteRepoLock.unlock();
        }

        Ref r = fetchResult.getAdvertisedRef(branch);
        if (r == null) {
            r = fetchResult.getAdvertisedRef(Constants.R_HEADS + branch);
        }

        if (r == null) {
            throw new RefNotAdvertisedException(
                MessageFormat.format(JGitText.get().couldNotGetAdvertisedRef, Constants.DEFAULT_REMOTE_NAME, branch));
        }

        MergeResult mergeResult = git.merge().include(r.getObjectId()).setStrategy(MergeStrategy.RECURSIVE).call();

        if (!mergeResult.getMergeStatus().isSuccessful()) {
            validateMergeConflict(mergeResult, true);
            throw new IOException("Can't merge: " + mergeResult.toString());
        }
    }

    private void validateMergeConflict(MergeResult mergeResult, boolean theirToOur) throws GitAPIException,
                                                                                    IOException {
        if (mergeResult != null && mergeResult.getMergeStatus() == MergeResult.MergeStatus.CONFLICTING) {
            ObjectId[] mergedCommits = mergeResult.getMergedCommits();
            Repository repository = git.getRepository();
            List<Ref> tags = git.tagList().call();

            String baseCommit = getVersionName(repository, tags, mergeResult.getBase());

            String ourCommit = null;
            String theirCommit = null;
            ObjectId ourId = null;
            ObjectId theirId = null;

            if (mergedCommits.length > 0) {
                String commit = getVersionName(repository, tags, mergedCommits[0]);
                if (theirToOur) {
                    ourId = mergedCommits[0];
                    ourCommit = commit;
                } else {
                    theirId = mergedCommits[0];
                    theirCommit = commit;
                }
            }
            if (mergedCommits.length > 1) {
                String commit = getVersionName(repository, tags, mergedCommits[1]);
                if (theirToOur) {
                    theirId = mergedCommits[1];
                    theirCommit = commit;
                } else {
                    ourId = mergedCommits[1];
                    ourCommit = commit;
                }
            }

            Set<String> conflictedFiles = mergeResult.getConflicts().keySet();
            Map<String, String> diffs = new HashMap<>();

            if (ourId != null && theirId != null) {
                AbstractTreeIterator ourTreeParser = prepareTreeParser(repository, ourId);
                AbstractTreeIterator theirTreeParser = prepareTreeParser(repository, theirId);

                List<DiffEntry> diff = git.diff()
                    .setOldTree(theirTreeParser)
                    .setNewTree(ourTreeParser)
                    .setPathFilter(PathFilterGroup.createFromStrings(conflictedFiles))
                    .call();

                for (DiffEntry entry : diff) {
                    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                    try (DiffFormatter formatter = new DiffFormatter(outputStream)) {
                        formatter.setRepository(repository);
                        formatter.format(entry);
                        String path = entry.getChangeType() == DiffEntry.ChangeType.DELETE ? entry.getOldPath()
                                                                                           : entry.getNewPath();
                        String comparison = outputStream.toString(StandardCharsets.UTF_8.name());
                        diffs.put(path, comparison);
                    }
                }
            }

            throw new MergeConflictException(diffs, baseCommit, ourCommit, theirCommit);
        }
    }

    private static AbstractTreeIterator prepareTreeParser(Repository repository, ObjectId objectId) throws IOException {
        // from the commit we can build the tree which allows us to construct the TreeParser
        // noinspection Duplicates
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit commit = walk.parseCommit(objectId);
            RevTree tree = walk.parseTree(commit.getTree().getId());

            CanonicalTreeParser treeParser = new CanonicalTreeParser();
            try (ObjectReader reader = repository.newObjectReader()) {
                treeParser.reset(reader, tree.getId());
            }

            walk.dispose();

            return treeParser;
        }
    }

    private FetchResult fetchAll() throws GitAPIException, IOException {
        FetchCommand fetchCommand = git.fetch();
        CredentialsProvider credentialsProvider = getCredentialsProvider();
        if (credentialsProvider != null) {
            fetchCommand.setCredentialsProvider(credentialsProvider);
        }
        fetchCommand.setRefSpecs(new RefSpec().setSourceDestination(Constants.R_HEADS + "*",
            Constants.R_REMOTES + Constants.DEFAULT_REMOTE_NAME + "/*"));
        fetchCommand.setRemoveDeletedRefs(true);
        fetchCommand.setTimeout(connectionTimeout);
        return fetchCommand.call();
    }

    private void push() throws GitAPIException, IOException {
        try {
            remoteRepoLock.lock();

            PushCommand push = git.push().setPushTags().add(branch).setTimeout(connectionTimeout);

            CredentialsProvider credentialsProvider = getCredentialsProvider();
            if (credentialsProvider != null) {
                push.setCredentialsProvider(credentialsProvider);
            }

            Iterable<PushResult> results = push.call();
            validatePushResults(results);
        } finally {
            remoteRepoLock.unlock();
        }
    }

    private void validatePushResults(Iterable<PushResult> results) throws IOException {
        for (PushResult result : results) {
            Collection<RemoteRefUpdate> remoteUpdates = result.getRemoteUpdates();
            for (RemoteRefUpdate remoteUpdate : remoteUpdates) {
                RemoteRefUpdate.Status status = remoteUpdate.getStatus();
                switch (status) {
                    case OK:
                    case UP_TO_DATE:
                    case NON_EXISTING:
                        // Successful operation. Continue.
                        break;
                    case REJECTED_NONFASTFORWARD:
                        throw new IOException(
                            "Remote ref update was rejected, as it would cause non fast-forward update.");
                    case REJECTED_NODELETE:
                        throw new IOException(
                            "Remote ref update was rejected, because remote side doesn't support/allow deleting refs.");
                    case REJECTED_REMOTE_CHANGED:
                        throw new IOException(
                            "Remote ref update was rejected, because old object id on remote repository wasn't the same as defined expected old object.");
                    case REJECTED_OTHER_REASON:
                        throw new IOException(remoteUpdate.getMessage());
                    case AWAITING_REPORT:
                        throw new IOException(
                            "Push process is awaiting update report from remote repository. This is a temporary state or state after critical error in push process.");
                    default:
                        throw new IOException(
                            "Push process returned with status " + status + " and message " + remoteUpdate
                                .getMessage());
                }
            }
        }
    }

    private <T> T iterate(String path, WalkCommand<T> command) throws IOException {
        Lock readLock = repositoryLock.readLock();
        try {
            log.debug("iterate(): lock");
            readLock.lock();

            org.eclipse.jgit.lib.Repository repository = git.getRepository();
            try (RevWalk walk = new RevWalk(repository)) {
                RevCommit commit = walk.parseCommit(resolveBranchId());
                RevTree tree = commit.getTree();

                // Create TreeWalk for root folder
                try (TreeWalk rootWalk = buildTreeWalk(repository, path, tree)) {
                    return command.apply(repository, rootWalk, path);
                } catch (FileNotFoundException e) {
                    return command.apply(repository, null, path);
                }
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        } finally {
            readLock.unlock();
            log.debug("iterate(): unlock");
        }
    }

    private <T> T iterateHistory(String name, HistoryVisitor<T> historyVisitor) throws IOException {
        Lock readLock = repositoryLock.readLock();
        try {
            log.debug("iterateHistory(): lock");
            readLock.lock();
            Iterator<RevCommit> iterator = git.log().add(resolveBranchId()).addPath(name).call().iterator();

            List<Ref> tags = git.tagList().call();

            while (iterator.hasNext()) {
                RevCommit commit = iterator.next();

                boolean stop = historyVisitor.visit(name, commit, getVersionName(git.getRepository(), tags, commit));
                if (stop) {
                    break;
                }
            }

            return historyVisitor.getResult();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        } finally {
            readLock.unlock();
            log.debug("iterateHistory(): unlock");
        }
    }

    private <T> T parseHistory(String name, String version, HistoryVisitor<T> historyVisitor) throws IOException {
        Lock readLock = repositoryLock.readLock();
        try {
            log.debug("parseHistory(): lock");
            readLock.lock();

            List<Ref> tags = git.tagList().call();

            try (RevWalk walk = new RevWalk(git.getRepository())) {
                RevCommit commit = walk.parseCommit(getCommitByVersion(version));
                historyVisitor.visit(name, commit, getVersionName(git.getRepository(), tags, commit));
                return historyVisitor.getResult();
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        } finally {
            readLock.unlock();
            log.debug("parseHistory(): unlock");
        }
    }

    /**
     * Reset work dir and index.
     */
    private void reset() {
        reset(null);
    }

    /**
     * Reset work dir, index and discard commit. if {@code commitToDiscard} is null, then just reset work dir and index.
     *
     * @param commitToDiscard if null, commit will not be discarded. If not null, commit with that id will be discarded.
     */
    private void reset(String commitToDiscard) {
        try {
            ResetCommand resetCommand = git.reset().setMode(ResetCommand.ResetType.HARD);
            if (commitToDiscard != null) {
                resetCommand.setRef(commitToDiscard + "^");
            }
            resetCommand.call();
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    private String getNextTagId() throws GitAPIException {
        List<Ref> call = git.tagList().call();
        long maxId = 0;
        for (Ref tagRef : call) {
            String name = getLocalTagName(tagRef);
            if (name.startsWith(tagPrefix)) {
                int num;
                try {
                    num = Integer.parseInt(name.substring(tagPrefix.length()));
                } catch (NumberFormatException e) {
                    log.debug("Tag {} is skipped because it doesn't contain version number", name);
                    continue;
                }
                if (num > maxId) {
                    maxId = num;
                }
            }
        }

        return String.valueOf(maxId + 1);
    }

    static String getVersionName(Repository repository, List<Ref> tags, ObjectId commitId) throws IOException {
        Ref tagRef = getTagRefForCommit(repository, tags, commitId);

        return tagRef != null ? getLocalTagName(tagRef) : commitId.getName();
    }

    private static Ref getTagRefForCommit(Repository repository, List<Ref> tags, ObjectId commitId) throws IOException {
        Ref tagRefForCommit = null;
        for (Ref tagRef : tags) {
            ObjectId objectId = repository.getRefDatabase().peel(tagRef).getPeeledObjectId();
            if (objectId == null) {
                objectId = tagRef.getObjectId();
            }

            if (objectId.equals(commitId)) {
                tagRefForCommit = tagRef;
                break;
            }
        }
        return tagRefForCommit;
    }

    private static String getLocalTagName(Ref tagRef) {
        String name = tagRef.getName();
        return name.startsWith(Constants.R_TAGS) ? name.substring(Constants.R_TAGS.length()) : name;
    }

    private void addTagToCommit(RevCommit commit) throws GitAPIException, IOException {
        pull();

        if (!tagPrefix.isEmpty()) {
            String tagName = tagPrefix + getNextTagId();
            git.tag().setObjectId(commit).setName(tagName).call();
        }
    }

    @Override
    public List<FileData> listFolders(String path) throws IOException {
        return iterate(path, new ListFoldersCommand());
    }

    @Override
    public FileData save(FileData folderData,
            Iterable<FileItem> files,
            ChangesetType changesetType) throws IOException {
        Lock writeLock = repositoryLock.writeLock();
        try {
            log.debug("save(folderData, files, changesetType): lock");
            writeLock.lock();

            reset();
            saveMultipleFiles(folderData, files, changesetType);
        } catch (IOException e) {
            reset();
            throw e;
        } catch (Exception e) {
            reset();
            throw new IOException(e.getMessage(), e);
        } finally {
            writeLock.unlock();
            log.debug("save(folderData, files, changesetType): unlock");
        }

        monitor.fireOnChange();
        return check(folderData.getName());
    }

    @Override
    public List<FileData> save(List<FolderItem> folderItems, ChangesetType changesetType) throws IOException {
        List<FileData> result = new ArrayList<>();
        Lock writeLock = repositoryLock.writeLock();
        String firstCommitId = null;
        try {
            log.debug("save(folderItems, changesetType): lock");
            writeLock.lock();
            reset();
            git.checkout().setName(branch).call();
            for (FolderItem folderItem : folderItems) {
                RevCommit commit = createCommit(folderItem.getData(), folderItem.getFiles(), changesetType);
                if (firstCommitId == null) {
                    firstCommitId = commit.getId().getName();
                }

                resolveAndMerge(folderItem.getData(), false, commit);
                addTagToCommit(commit);
            }
            push();
        } catch (IOException e) {
            reset(firstCommitId);
            throw e;
        } catch (Exception e) {
            reset(firstCommitId);
            throw new IOException(e.getMessage(), e);
        } finally {
            writeLock.unlock();
            log.debug("save(folderItems, changesetType): unlock");
        }
        monitor.fireOnChange();

        for (FolderItem folderItem : folderItems) {
            result.add(check(folderItem.getData().getName()));
        }
        return result;
    }

    private void saveMultipleFiles(FileData folderData,
            Iterable<FileItem> files,
            ChangesetType changesetType) throws IOException {

        String commitId = null;
        try {
            String parentVersion = folderData.getVersion();
            boolean checkoutOldVersion = isCheckoutOldVersion(folderData.getName(), parentVersion);
            git.checkout().setName(checkoutOldVersion ? parentVersion : branch).call();

            RevCommit commit = createCommit(folderData, files, changesetType);
            commitId = commit.getId().getName();

            resolveAndMerge(folderData, checkoutOldVersion, commit);
            addTagToCommit(commit);

            push();
        } catch (IOException e) {
            reset(commitId);
            throw e;
        } catch (Exception e) {
            reset(commitId);
            throw new IOException(e.getMessage(), e);
        }
    }

    private RevCommit createCommit(FileData folderData,
            Iterable<FileItem> files,
            ChangesetType changesetType) throws IOException, GitAPIException {
        String relativeFolder = folderData.getName();

        List<String> changedFiles = new ArrayList<>();

        // Add new files and update existing ones
        List<File> savedFiles = new ArrayList<>();
        for (FileItem change : files) {
            File file = new File(localRepositoryPath, change.getData().getName());
            savedFiles.add(file);
            applyChangeInWorkspace(change, changedFiles);
        }

        if (changesetType == ChangesetType.FULL) {
            // Remove absent files
            String basePath = new File(localRepositoryPath).getAbsolutePath();
            File folder = new File(localRepositoryPath, relativeFolder);
            removeAbsentFiles(basePath, folder, savedFiles, changedFiles);
        }

        CommitCommand commitCommand = git.commit()
            .setMessage(formatComment(CommitType.SAVE, folderData))
            .setCommitter(userDisplayName != null ? userDisplayName : folderData.getAuthor(),
                userEmail != null ? userEmail : "");

        return commitChangedFiles(commitCommand, changedFiles);
    }

    private void applyChangeInWorkspace(FileItem change, Collection<String> changedFiles) throws IOException,
                                                                                          GitAPIException {
        File file = new File(localRepositoryPath, change.getData().getName());
        createParent(file);

        InputStream stream = change.getStream();
        if (stream != null) {
            try (FileOutputStream output = new FileOutputStream(file)) {
                IOUtils.copy(stream, output);
            }
            git.add().addFilepattern(change.getData().getName()).call();
            changedFiles.add(change.getData().getName());
        } else {
            if (file.exists()) {
                git.rm().addFilepattern(change.getData().getName()).call();
                changedFiles.add(change.getData().getName());
            }
        }
    }

    private RevCommit commitChangedFiles(CommitCommand commitCommand,
            Collection<String> changedFiles) throws GitAPIException {
        RevCommit commit;
        if (git.status().call().getUncommittedChanges().isEmpty()) {
            // For the cases:
            // 1) User modified a project, then manually reverted, then pressed save.
            // 2) Copy project that doesn't have rules.xml, check "Copy old revisions". The last one commit should
            // have changed rules.xml with changed project name but the project doesn't have rules.xml so there are
            // no changes
            // 3) Try to deploy several times same deploy configuration. For example if we need to trigger
            // webservices redeployment without actually changing projects.
            commit = commitCommand.setAllowEmpty(true).call();
        } else {
            for (String fileName : changedFiles) {
                commitCommand.setOnly(fileName);
            }
            commit = commitCommand.call();
        }
        return commit;
    }

    private void resolveAndMerge(FileData folderData,
            boolean checkoutOldVersion,
            RevCommit commit) throws GitAPIException, IOException {
        ConflictResolveData conflictResolveData = folderData.getAdditionalData(ConflictResolveData.class);
        RevCommit lastCommit = commit;

        if (conflictResolveData != null) {
            lastCommit = resolveConflict(folderData.getAuthor(), conflictResolveData);
        }

        if (checkoutOldVersion || conflictResolveData != null) {
            // Merge detached commit to existing branch.
            git.checkout().setName(branch).call();
            MergeResult mergeDetached = git.merge().include(lastCommit.getId()).call();
            validateMergeConflict(mergeDetached, false);
        }
    }

    private RevCommit resolveConflict(String author, ConflictResolveData conflictResolveData) throws GitAPIException,
                                                                                              IOException {
        // Merge with a commit we have a conflict.
        MergeResult mergeResult = git.merge()
            .include(getCommitByVersion(conflictResolveData.getCommitToMerge()))
            .call();

        if (mergeResult.getMergeStatus() != MergeResult.MergeStatus.CONFLICTING) {
            log.debug("Merge status: {}", mergeResult.getMergeStatus());
            throw new IOException("There is no merge conflict, nothing to resolve.");
        }

        // Resolve merge conflict.
        String mergeMessage = conflictResolveData.getMergeMessage();
        if (mergeMessage == null) {
            mergeMessage = "Merge";
        }
        CommitCommand conflictResolveCommit = git.commit()
            .setMessage(mergeMessage)
            .setCommitter(userDisplayName != null ? userDisplayName : author, userEmail != null ? userEmail : "");

        Status status = git.status().call();

        Set<String> changedFiles = new HashSet<>();
        for (FileItem change : conflictResolveData.getResolvedFiles()) {
            applyChangeInWorkspace(change, changedFiles);
        }

        for (String changed : status.getChanged()) {
            if (!changedFiles.contains(changed)) {
                git.add().addFilepattern(changed).call();
                changedFiles.add(changed);
            }
        }
        for (String added : status.getAdded()) {
            if (!changedFiles.contains(added)) {
                git.add().addFilepattern(added).call();
                changedFiles.add(added);
            }
        }
        for (String removed : status.getRemoved()) {
            if (!changedFiles.contains(removed)) {
                git.rm().addFilepattern(removed).call();
                changedFiles.add(removed);
            }
        }

        return commitChangedFiles(conflictResolveCommit, changedFiles);
    }

    private ObjectId getCommitByVersion(String version) throws IOException {
        Ref ref = git.getRepository().findRef(version);
        if (ref == null) {
            // Version is a hash for commit
            return git.getRepository().resolve(version);
        }

        // Version is a tag.
        ObjectId objectId = git.getRepository().getRefDatabase().peel(ref).getPeeledObjectId();
        // Not annotated tags return null for getPeeledObjectId().
        return objectId == null ? ref.getObjectId() : objectId;
    }

    @Override
    public Features supports() {
        return new FeaturesBuilder(this).setSupportsUniqueFileId(true).build();
    }

    @Override
    public String getBranch() {
        return branch;
    }

    @Override
    public void createBranch(String projectName, String newBranch) throws IOException {
        Lock writeLock = repositoryLock.writeLock();
        try {
            log.debug("createBranch(): lock");
            writeLock.lock();

            reset();

            // If newBranch doesn't exist, create it.
            boolean branchAbsents = git.getRepository().findRef(newBranch) == null;
            if (branchAbsents) {
                // Checkout existing branch
                git.checkout().setName(branch).call();

                // Create new branch
                git.branchCreate().setName(newBranch).call();
                pushBranch(new RefSpec().setSource(newBranch).setDestination(Constants.R_HEADS + newBranch));
            }

            // Add mapping for projectName and newBranch
            List<String> projectBranches = branches.get(projectName);
            if (projectBranches == null) {
                projectBranches = new ArrayList<>();
                projectBranches.add(branch); // Add main branch
                branches.put(projectName, projectBranches);
            }
            if (!projectBranches.contains(newBranch)) {
                projectBranches.add(newBranch);
            }

            saveBranches();
        } catch (IOException e) {
            reset();
            try {
                git.branchDelete().setBranchNames(newBranch).call();
            } catch (Exception ignored) {
            }
            throw e;
        } catch (Exception e) {
            reset();
            try {
                git.branchDelete().setBranchNames(newBranch).call();
            } catch (Exception ignored) {
            }
            throw new IOException(e.getMessage(), e);
        } finally {
            writeLock.unlock();
            log.debug("createBranch(): unlock");
        }
    }

    @Override
    public void deleteBranch(String projectName, String branch) throws IOException {
        Lock writeLock = repositoryLock.writeLock();
        try {
            log.debug("deleteBranch(): lock");
            writeLock.lock();

            reset();

            if (projectName == null) {
                // Remove the branch from all mappings.
                for (List<String> projectBranches : branches.values()) {
                    projectBranches.remove(branch);
                }
                saveBranches();

                // Remove the branch from git itself.
                // Can't delete checked out branch. So we check out another branch instead.
                git.checkout().setName(baseBranch).call();
                git.branchDelete().setBranchNames(branch).setForce(true).call();
                pushBranch(new RefSpec().setSource(null).setDestination(Constants.R_HEADS + branch));
                branchRepos.remove(branch);
            } else {
                // Remove branch mapping for specific project only.
                List<String> projectBranches = branches.get(projectName);
                if (projectBranches != null) {
                    projectBranches.remove(branch);
                    saveBranches();
                }
            }
        } catch (IOException e) {
            reset();
            throw e;
        } catch (Exception e) {
            reset();
            throw new IOException(e.getMessage(), e);
        } finally {
            writeLock.unlock();
            log.debug("deleteBranch(): unlock");
        }
    }

    @Override
    public List<String> getBranches(String projectName) throws IOException {
        Lock readLock = repositoryLock.readLock();
        try {
            log.debug("getBranches(): lock");
            readLock.lock();
            if (projectName == null) {
                // Return all available branches
                TreeSet<String> branchNames = getAvailableBranches();

                // Local branches absent in repository may be needed to uncheck them in UI.
                for (List<String> projectBranches : branches.values()) {
                    branchNames.addAll(projectBranches);
                }

                return new ArrayList<>(branchNames);
            } else {
                // Return branches mapped to a specific project
                List<String> projectBranches = branches.get(projectName);
                List<String> result;
                if (projectBranches == null) {
                    result = new ArrayList<>(Collections.singletonList(branch));
                } else {
                    result = new ArrayList<>(projectBranches);
                    result.sort(String.CASE_INSENSITIVE_ORDER);
                }
                return result;
            }
        } catch (GitAPIException e) {
            throw new IOException(e);
        } finally {
            readLock.unlock();
            log.debug("getBranches(): unlock");
        }
    }

    @Override
    public GitRepository forBranch(String branch) throws IOException {
        GitRepository repository;

        Lock readLock = repositoryLock.readLock();
        try {
            log.debug("forBranch(): read: lock");
            readLock.lock();
            repository = branchRepos.get(branch);
            if (repository == null && git.getRepository().findRef(branch) == null) {
                List<Ref> refs = git.branchList().setListMode(ListBranchCommand.ListMode.REMOTE).call();

                boolean branchExist = false;
                String remoteBranchName = Constants.R_REMOTES + Constants.DEFAULT_REMOTE_NAME + "/" + branch;
                for (Ref ref : refs) {
                    String name = ref.getName();
                    if (remoteBranchName.equals(name)) {
                        branchExist = true;
                        break;
                    }
                }

                if (!branchExist) {
                    throw new IOException("Can't find branch '" + branch + "'");
                }
            }
        } catch (GitAPIException e) {
            throw new IOException(e);
        } finally {
            readLock.unlock();
            log.debug("forBranch(): read: unlock");
        }
        if (repository == null) {
            Lock writeLock = repositoryLock.writeLock();
            try {
                log.debug("forBranch(): write: lock");
                writeLock.lock();

                repository = branchRepos.get(branch);
                if (repository == null) {
                    repository = createRepository(branch);
                    branchRepos.put(branch, repository);
                }
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException(e);
            } finally {
                writeLock.unlock();
                log.debug("forBranch(): write: unlock");
            }
        }

        return repository;
    }

    private GitRepository createRepository(String branch) throws IOException, GitAPIException {
        if (git.getRepository().findRef(branch) == null) {
            createRemoteTrackingBranch(branch);
        }

        GitRepository repo = new GitRepository();

        repo.setUri(uri);
        repo.setLogin(login);
        repo.setPassword(password);
        repo.credentialsProvider = credentialsProvider;
        repo.setUserDisplayName(userDisplayName);
        repo.setUserEmail(userEmail);
        repo.setLocalRepositoryPath(localRepositoryPath);
        repo.setBranch(branch);
        repo.baseBranch = baseBranch; // Base branch is only one
        repo.setTagPrefix(tagPrefix);
        repo.setListenerTimerPeriod(listenerTimerPeriod);
        repo.setConnectionTimeout(connectionTimeout);
        repo.setCommentTemplate(commentTemplate);
        repo.setGitSettingsPath(gitSettingsPath);
        repo.git = Git.open(new File(localRepositoryPath));
        repo.repositoryLock = repositoryLock; // must be common for all instances because git
        // repository is same
        repo.remoteRepoLock = remoteRepoLock; // must be common for all instances because git
        // repository is same
        repo.branches = branches; // Can be shared between instances
        repo.monitor = monitor;
        return repo;
    }

    private void createRemoteTrackingBranch(String branch) throws GitAPIException {
        git.branchCreate()
            .setName(branch)
            .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
            .setStartPoint(Constants.DEFAULT_REMOTE_NAME + "/" + branch)
            .call();
    }

    TreeSet<String> getAvailableBranches() throws GitAPIException {
        TreeSet<String> branchNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        List<Ref> refs = git.branchList().call();
        for (Ref ref : refs) {
            String name = ref.getName();
            if (name.startsWith(Constants.R_HEADS)) {
                name = name.substring(Constants.R_HEADS.length());
                branchNames.add(name);
            }
        }
        return branchNames;
    }

    private void pushBranch(RefSpec refSpec) throws GitAPIException, IOException {
        PushCommand push = git.push().setRefSpecs(refSpec).setTimeout(connectionTimeout);

        CredentialsProvider credentialsProvider = getCredentialsProvider();
        if (credentialsProvider != null) {
            push.setCredentialsProvider(credentialsProvider);
        }

        Iterable<PushResult> results = push.call();
        validatePushResults(results);
    }

    private void readBranches() throws IOException {
        branches.clear();

        if (StringUtils.isBlank(gitSettingsPath)) {
            return;
        }

        File settings = new File(new File(gitSettingsPath), "branches.properties");
        if (settings.isFile()) {
            try (InputStreamReader in = new InputStreamReader(new FileInputStream(settings), StandardCharsets.UTF_8)) {
                Properties properties = new Properties();
                properties.load(in);
                String numStr = properties.getProperty("projects.number");
                if (numStr == null) {
                    return;
                }

                int num = Integer.parseInt(numStr);
                for (int i = 1; i <= num; i++) {
                    String name = properties.getProperty("project." + i + ".name");
                    String branchesStr = properties.getProperty("project." + i + ".branches");
                    if (StringUtils.isBlank(name) || StringUtils.isBlank(branchesStr)) {
                        continue;
                    }

                    branches.put(name, new ArrayList<>(Arrays.asList(branchesStr.split(","))));
                }
            }
        }

    }

    private void saveBranches() throws IOException {
        if (StringUtils.isBlank(gitSettingsPath)) {
            return;
        }
        File parent = new File(gitSettingsPath);
        if (!parent.mkdirs() && !parent.exists()) {
            throw new FileNotFoundException("Can't create folder " + gitSettingsPath);
        }
        File settings = new File(parent, "branches.properties");
        try (OutputStreamWriter out = new OutputStreamWriter(new FileOutputStream(settings), StandardCharsets.UTF_8)) {
            Properties properties = new Properties();
            properties.setProperty("projects.number", String.valueOf(branches.size()));

            int i = 1;
            for (Map.Entry<String, List<String>> entry : branches.entrySet()) {
                properties.setProperty("project." + i + ".name", entry.getKey());
                properties.setProperty("project." + i + ".branches", StringUtils.join(entry.getValue(), ","));

                i++;
            }
            properties.store(out, null);
        }
    }

    private void removeAbsentFiles(String baseAbsolutePath,
            File directory,
            Collection<File> toSave,
            List<String> changedFiles) throws GitAPIException {
        File[] found = directory.listFiles();

        if (found != null) {
            for (File file : found) {
                if (file.isDirectory()) {
                    removeAbsentFiles(baseAbsolutePath, file, toSave, changedFiles);
                } else {
                    if (!toSave.contains(file)) {
                        String relativePath = file.getAbsolutePath()
                            .substring(baseAbsolutePath.length())
                            .replace('\\', '/');
                        if (relativePath.startsWith("/")) {
                            relativePath = relativePath.substring(1);
                        }
                        git.rm().addFilepattern(relativePath).call();
                        changedFiles.add(relativePath);
                    }
                }
            }
        }
    }

    private void createParent(File file) throws FileNotFoundException {
        File parentFile = file.getParentFile();
        if (!parentFile.mkdirs() && !parentFile.exists()) {
            throw new FileNotFoundException("Can't create the folder " + parentFile.getAbsolutePath());
        }
    }

    private URI getUri(String uriOrPath) {
        if (uriOrPath == null) {
            return null;
        }
        try {
            return new URI(uriOrPath);
        } catch (URISyntaxException e) {
            // uri can be a folder path. It's not valid URI but git accepts paths too.
            return new File(uriOrPath).toURI();
        }
    }

    private String escapeCurlyBrackets(String value) {
        String ret = value.replaceAll("\\{(?![012]})", "'{'");
        return ret.replaceAll("(?<!\\{[012])}", "'}'");
    }

    private String formatComment(CommitType commitType, FileData data) {
        String comment = StringUtils.trimToEmpty(data.getComment());
        return MessageFormat.format(escapedCommentTemplate, commitType, comment, data.getAuthor());
    }

    private boolean isCheckoutOldVersion(String path, String baseVersion) throws GitAPIException, IOException {
        if (baseVersion != null) {
            List<Ref> tags = git.tagList().call();

            Iterator<RevCommit> iterator = git.log()
                .add(resolveBranchId())
                .addPath(path)
                .setMaxCount(1)
                .call()
                .iterator();
            if (iterator.hasNext()) {
                String lastVersion = getVersionName(git.getRepository(), tags, iterator.next());
                return !baseVersion.equals(lastVersion);
            } else {
                throw new FileNotFoundException(
                    "Can't find commit for path '" + path + "' and version '" + baseVersion + "'");
            }
        }

        return false;
    }

    @Override
    public boolean isValidBranchName(String s) {
        return s != null && Repository.isValidRefName(Constants.R_HEADS + s);
    }

    @Override
    public boolean branchExists(String branch) throws IOException {
        for (String existedBranch : getBranches(null)) {
            if (existedBranch.equalsIgnoreCase(branch)) {
                return true;
            }
        }
        return false;
    }

    private CredentialsProvider getCredentialsProvider() throws IOException {
        if (credentialsProvider != null && credentialsProvider.isHasAuthorizationFailure()) {
            // We can't use this credentials provider anymore. If we continue, the server can lock us for brute forcing.
            throw new IOException("Git repository credentials are incorrect. Please update repository configuration.");
        }
        return credentialsProvider;
    }

    private class GitRevisionGetter implements RevisionGetter {
        @Override
        public Object getRevision() {
            try {
                return getLastRevision();
            } catch (Exception e) {
                log.warn(e.getMessage(), e);
                return null;
            }
        }
    }

    public interface WalkCommand<T> {
        T apply(org.eclipse.jgit.lib.Repository repository, TreeWalk rootWalk, String baseFolder) throws IOException,
                                                                                                  GitAPIException;
    }

    public interface HistoryVisitor<T> {
        /**
         * Visit commit for a file with a path {@code fullPath}
         *
         * @param fullPath full path to the file
         * @param commit visiting commit
         * @param commitVersion commit version. Either tag name or commit hash.
         * @return true if we should stop iterating history (we found needed information) and false if not found or
         *         should iterate all commits
         */
        boolean visit(String fullPath, RevCommit commit, String commitVersion) throws IOException, GitAPIException;

        /**
         * Get accumulated result
         */
        T getResult();
    }

    private class ListCommand implements WalkCommand<List<FileData>> {
        private final ObjectId start;

        private ListCommand(ObjectId start) {
            this.start = start;
        }

        @Override
        public List<FileData> apply(org.eclipse.jgit.lib.Repository repository,
                TreeWalk rootWalk,
                String baseFolder) throws IOException {
            if (rootWalk != null) {
                // Iterate files in folder
                List<FileData> files = new ArrayList<>();
                if (rootWalk.getFilter() == TreeFilter.ALL) {
                    while (rootWalk.next()) {
                        files.add(createFileData(rootWalk, baseFolder, start));
                    }
                } else {
                    if (rootWalk.getTreeCount() > 0) {
                        try (TreeWalk dirWalk = new TreeWalk(repository)) {
                            dirWalk.addTree(rootWalk.getObjectId(0));
                            dirWalk.setRecursive(true);

                            while (dirWalk.next()) {
                                files.add(createFileData(dirWalk, baseFolder, start));
                            }
                        }
                    }
                }

                return files;
            } else {
                return Collections.emptyList();
            }
        }
    }

    private class ListFoldersCommand implements WalkCommand<List<FileData>> {
        @Override
        public List<FileData> apply(org.eclipse.jgit.lib.Repository repository,
                TreeWalk rootWalk,
                String baseFolder) throws IOException {
            if (rootWalk != null) {
                if (rootWalk.getFilter() == TreeFilter.ALL) {
                    return collectFolderData(rootWalk, baseFolder);
                } else {
                    if (rootWalk.getTreeCount() > 0) {
                        try (TreeWalk dirWalk = new TreeWalk(repository)) {
                            dirWalk.addTree(rootWalk.getObjectId(0));
                            return collectFolderData(dirWalk, baseFolder);
                        }
                    }
                }
            }

            return Collections.emptyList();
        }

        private List<FileData> collectFolderData(TreeWalk rootWalk, String baseFolder) throws IOException {
            List<FileData> files = new ArrayList<>();
            rootWalk.setRecursive(false);
            ObjectId start = resolveBranchId();
            while (rootWalk.next()) {
                if ((rootWalk.getFileMode().getBits() & FileMode.TYPE_TREE) != 0) {
                    files.add(createFileData(rootWalk, baseFolder, start));
                }
            }

            return files;
        }
    }

    private class CheckCommand implements WalkCommand<FileData> {
        @Override
        public FileData apply(org.eclipse.jgit.lib.Repository repository,
                TreeWalk rootWalk,
                String baseFolder) throws IOException {
            if (rootWalk != null) {
                return createFileData(rootWalk, "", resolveBranchId());
            } else {
                return null;
            }
        }
    }

    private class ReadCommand implements WalkCommand<FileItem> {
        @Override
        public FileItem apply(org.eclipse.jgit.lib.Repository repository,
                TreeWalk rootWalk,
                String baseFolder) throws IOException {
            if (rootWalk != null) {
                FileData fileData = createFileData(rootWalk, "", resolveBranchId());
                ObjectLoader loader = repository.open(rootWalk.getObjectId(0));
                return new FileItem(fileData, loader.openStream());
            } else {
                return null;
            }
        }
    }

    private class ListHistoryVisitor implements HistoryVisitor<List<FileData>> {
        private final org.eclipse.jgit.lib.Repository repository;
        private final List<FileData> history = new ArrayList<>();

        private ListHistoryVisitor() {
            repository = git.getRepository();
        }

        @Override
        public boolean visit(String fullPath, RevCommit commit, String commitVersion) throws IOException {
            RevTree tree = commit.getTree();

            try (TreeWalk rootWalk = buildTreeWalk(repository, fullPath, tree)) {
                history.add(createFileData(rootWalk, commit));
            } catch (FileNotFoundException e) {
                log.debug("File '{}' is absent in the commit {}", fullPath, commitVersion, e);
                FileData data = new LazyFileData(branch,
                    fullPath,
                    new File(localRepositoryPath),
                    commit,
                    null,
                    escapedCommentTemplate);
                // Must mark it as deleted explicitly because the file can be erased outside of WebStudio.
                data.setDeleted(true);

                history.add(data);
            }

            return false;
        }

        @Override
        public List<FileData> getResult() {
            Collections.reverse(history);
            return history;
        }
    }

    private class ListFilesHistoryVisitor implements HistoryVisitor<List<FileData>> {
        private final String version;
        private final org.eclipse.jgit.lib.Repository repository;
        private final List<FileData> history = new ArrayList<>();

        private ListFilesHistoryVisitor(String version) {
            this.version = version;
            repository = git.getRepository();
        }

        @Override
        public boolean visit(String fullPath, RevCommit commit, String commitVersion) throws IOException {
            if (commitVersion.equals(version)) {
                RevTree tree = commit.getTree();

                try (TreeWalk rootWalk = buildTreeWalk(repository, fullPath, tree)) {
                    history.addAll(new ListCommand(commit.getId()).apply(repository, rootWalk, fullPath));
                } catch (FileNotFoundException ignored) {
                }

                return true;
            }

            return false;
        }

        @Override
        public List<FileData> getResult() {
            Collections.reverse(history);
            return history;
        }
    }

    private class CheckHistoryVisitor implements HistoryVisitor<FileData> {
        private final String version;
        private final org.eclipse.jgit.lib.Repository repository;
        private FileData result;

        private CheckHistoryVisitor(String version) {
            this.version = version;
            repository = git.getRepository();
        }

        @Override
        public boolean visit(String fullPath, RevCommit commit, String commitVersion) throws IOException {
            if (commitVersion.equals(version)) {
                RevTree tree = commit.getTree();

                try (TreeWalk rootWalk = buildTreeWalk(repository, fullPath, tree)) {
                    result = createFileData(rootWalk, commit);
                } catch (FileNotFoundException e) {
                    result = null;
                }

                return true;
            }

            return false;
        }

        @Override
        public FileData getResult() {
            return result;
        }
    }

    private class ReadHistoryVisitor implements HistoryVisitor<FileItem> {
        private final String version;
        private final org.eclipse.jgit.lib.Repository repository;
        private FileItem result;

        private ReadHistoryVisitor(String version) {
            this.version = version;
            repository = git.getRepository();
        }

        @Override
        public boolean visit(String fullPath, RevCommit commit, String commitVersion) throws IOException {
            if (commitVersion.equals(version)) {
                RevTree tree = commit.getTree();

                try (TreeWalk rootWalk = buildTreeWalk(repository, fullPath, tree)) {
                    FileData fileData = createFileData(rootWalk, commit);
                    ObjectLoader loader = repository.open(rootWalk.getObjectId(0));
                    result = new FileItem(fileData, loader.openStream());
                } catch (FileNotFoundException e) {
                    result = null;
                }

                return true;
            }

            return false;
        }

        @Override
        public FileItem getResult() {
            return result;
        }
    }
}
