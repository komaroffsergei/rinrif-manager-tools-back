package ru.reinform.rinrif.managertools.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;

class GitLogService {
    GitLogService() {
    }

    List<CommitRecord> listCommits(Path localPath, GitLogOptions options) {
        Repository repository = null;
        RevWalk walk = null;
        try {
            repository = GitSupport.openRepository(localPath);
            walk = new RevWalk(repository);
            RevCommit start = readCommit(repository, walk, options.resolvedRef);
            Date dateFrom = parseDate(options.dateFrom);
            Date dateTo = parseDate(options.dateTo);

            if (options.firstParent) {
                return listFirstParentCommits(walk, start, options, dateFrom, dateTo);
            }

            List<CommitRecord> result = new ArrayList<CommitRecord>();
            walk.sort(RevSort.COMMIT_TIME_DESC);
            walk.markStart(start);
            for (RevCommit commit : walk) {
                if (isVisible(commit, options, dateFrom, dateTo)) {
                    result.add(toCommitRecord(commit));
                    if (result.size() >= options.scanLimit) {
                        break;
                    }
                }
            }
            return result;
        } catch (IOException error) {
            throw new AppException("SEARCH_FAILED", "Failed to read commit list.", 500);
        } finally {
            if (walk != null) {
                walk.close();
            }
            if (repository != null) {
                repository.close();
            }
        }
    }

    String showDiff(Path localPath, String sha) {
        Repository repository = null;
        RevWalk walk = null;
        DiffFormatter formatter = null;
        try {
            repository = GitSupport.openRepository(localPath);
            walk = new RevWalk(repository);
            RevCommit commit = readCommit(repository, walk, sha);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            formatter = new DiffFormatter(output);
            formatter.setRepository(repository);
            formatter.setDetectRenames(true);
            formatter.setContext(0);
            formatter.format(oldTreeIterator(repository, walk, commit), treeIterator(repository, commit.getTree()));
            formatter.flush();
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new AppException("DIFF_PARSE_FAILED", "Failed to read commit diff.", 500);
        } finally {
            if (formatter != null) {
                formatter.close();
            }
            if (walk != null) {
                walk.close();
            }
            if (repository != null) {
                repository.close();
            }
        }
    }

    String showDiff(Path localPath, String baseSha, String targetSha) {
        Repository repository = null;
        RevWalk walk = null;
        DiffFormatter formatter = null;
        try {
            repository = GitSupport.openRepository(localPath);
            walk = new RevWalk(repository);
            RevCommit base = readCommit(repository, walk, baseSha);
            RevCommit target = readCommit(repository, walk, targetSha);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            formatter = new DiffFormatter(output);
            formatter.setRepository(repository);
            formatter.setDetectRenames(true);
            formatter.setContext(0);
            formatter.format(treeIterator(repository, base.getTree()), treeIterator(repository, target.getTree()));
            formatter.flush();
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new AppException("DIFF_PARSE_FAILED", "Failed to read release diff.", 500);
        } finally {
            if (formatter != null) {
                formatter.close();
            }
            if (walk != null) {
                walk.close();
            }
            if (repository != null) {
                repository.close();
            }
        }
    }

    List<String> listChangedFiles(Path localPath, String sha) {
        Repository repository = null;
        RevWalk walk = null;
        DiffFormatter formatter = null;
        try {
            repository = GitSupport.openRepository(localPath);
            walk = new RevWalk(repository);
            RevCommit commit = readCommit(repository, walk, sha);
            formatter = new DiffFormatter(DisabledOutputStream.INSTANCE);
            formatter.setRepository(repository);
            formatter.setDetectRenames(true);
            List<DiffEntry> diffs = formatter.scan(oldTreeIterator(repository, walk, commit), treeIterator(repository, commit.getTree()));
            List<String> files = new ArrayList<String>();
            for (DiffEntry diff : diffs) {
                String path = diff.getNewPath();
                if (DiffEntry.DEV_NULL.equals(path)) {
                    path = diff.getOldPath();
                }
                if (!CoreUtils.safe(path).trim().isEmpty()) {
                    files.add(path);
                }
            }
            return files;
        } catch (IOException error) {
            throw new AppException("DIFF_PARSE_FAILED", "Failed to read changed file list.", 500);
        } finally {
            if (formatter != null) {
                formatter.close();
            }
            if (walk != null) {
                walk.close();
            }
            if (repository != null) {
                repository.close();
            }
        }
    }

    List<String> listChangedFiles(Path localPath, String baseSha, String targetSha) {
        Repository repository = null;
        RevWalk walk = null;
        DiffFormatter formatter = null;
        try {
            repository = GitSupport.openRepository(localPath);
            walk = new RevWalk(repository);
            RevCommit target = readCommit(repository, walk, targetSha);
            RevCommit base = readCommit(repository, walk, baseSha);
            formatter = new DiffFormatter(DisabledOutputStream.INSTANCE);
            formatter.setRepository(repository);
            formatter.setDetectRenames(true);
            List<DiffEntry> diffs = formatter.scan(treeIterator(repository, base.getTree()), treeIterator(repository, target.getTree()));
            List<String> files = new ArrayList<String>();
            for (DiffEntry diff : diffs) {
                String path = diff.getNewPath();
                if (DiffEntry.DEV_NULL.equals(path)) {
                    path = diff.getOldPath();
                }
                if (!CoreUtils.safe(path).trim().isEmpty()) {
                    files.add(path);
                }
            }
            return files;
        } catch (IOException error) {
            throw new AppException("DIFF_PARSE_FAILED", "Failed to read release changed file list.", 500);
        } finally {
            if (formatter != null) {
                formatter.close();
            }
            if (walk != null) {
                walk.close();
            }
            if (repository != null) {
                repository.close();
            }
        }
    }

    String readFileAt(Path localPath, String sha, String filePath) {
        Repository repository = null;
        RevWalk walk = null;
        TreeWalk treeWalk = null;
        try {
            repository = GitSupport.openRepository(localPath);
            walk = new RevWalk(repository);
            RevCommit commit = readCommit(repository, walk, sha);
            treeWalk = TreeWalk.forPath(repository, filePath, commit.getTree());
            if (treeWalk == null) {
                return null;
            }
            byte[] content = repository.open(treeWalk.getObjectId(0)).getBytes(1024 * 1024);
            return new String(content, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new AppException("FILE_READ_FAILED", "Failed to read file at commit.", 500);
        } finally {
            if (treeWalk != null) {
                treeWalk.close();
            }
            if (walk != null) {
                walk.close();
            }
            if (repository != null) {
                repository.close();
            }
        }
    }

    List<CommitRecord> listRangeCommits(Path localPath, String baseSha, String targetSha, int limit) {
        Repository repository = null;
        RevWalk walk = null;
        try {
            repository = GitSupport.openRepository(localPath);
            walk = new RevWalk(repository);
            RevCommit target = readCommit(repository, walk, targetSha);
            walk.sort(RevSort.TOPO);
            walk.sort(RevSort.COMMIT_TIME_DESC, true);
            walk.markStart(target);
            if (!CoreUtils.safe(baseSha).trim().isEmpty()) {
                walk.markUninteresting(readCommit(repository, walk, baseSha));
            }
            List<CommitRecord> result = new ArrayList<CommitRecord>();
            for (RevCommit commit : walk) {
                result.add(toCommitRecord(commit));
                if (result.size() >= limit) {
                    break;
                }
            }
            return result;
        } catch (IOException error) {
            throw new AppException("SEARCH_FAILED", "Failed to read release commit range.", 500);
        } finally {
            if (walk != null) {
                walk.close();
            }
            if (repository != null) {
                repository.close();
            }
        }
    }

    String parentOf(Path localPath, String sha) {
        Repository repository = null;
        RevWalk walk = null;
        try {
            repository = GitSupport.openRepository(localPath);
            walk = new RevWalk(repository);
            RevCommit commit = readCommit(repository, walk, sha);
            return commit.getParentCount() == 0 ? null : walk.parseCommit(commit.getParent(0).getId()).getName();
        } catch (IOException error) {
            throw new AppException("SEARCH_FAILED", "Failed to read commit parent.", 500);
        } finally {
            if (walk != null) {
                walk.close();
            }
            if (repository != null) {
                repository.close();
            }
        }
    }

    boolean isAncestor(Path localPath, String ancestorSha, String descendantSha) {
        Repository repository = null;
        RevWalk walk = null;
        try {
            repository = GitSupport.openRepository(localPath);
            walk = new RevWalk(repository);
            RevCommit ancestor = readCommit(repository, walk, ancestorSha);
            RevCommit descendant = readCommit(repository, walk, descendantSha);
            return walk.isMergedInto(ancestor, descendant);
        } catch (IOException error) {
            throw new AppException("SEARCH_FAILED", "Failed to verify Git ancestry.", 500);
        } finally {
            if (walk != null) {
                walk.close();
            }
            if (repository != null) {
                repository.close();
            }
        }
    }

    String findBaseBeforeCommits(Path localPath, String targetSha, List<String> commitShas) {
        Repository repository = null;
        RevWalk walk = null;
        try {
            repository = GitSupport.openRepository(localPath);
            walk = new RevWalk(repository);
            RevCommit current = readCommit(repository, walk, targetSha);
            Set<ObjectId> relevant = new HashSet<ObjectId>();
            for (String sha : commitShas) {
                ObjectId id = repository.resolve(CoreUtils.safe(sha) + "^{commit}");
                if (id != null) {
                    relevant.add(id);
                }
            }
            if (relevant.isEmpty()) {
                return current.getParentCount() == 0 ? null : current.getParent(0).getName();
            }
            while (current.getParentCount() > 0) {
                RevCommit parent = walk.parseCommit(current.getParent(0).getId());
                boolean parentContainsRelevant = false;
                for (ObjectId relevantId : relevant) {
                    RevWalk ancestryWalk = new RevWalk(repository);
                    try {
                        if (ancestryWalk.isMergedInto(ancestryWalk.parseCommit(relevantId), ancestryWalk.parseCommit(parent.getId()))) {
                            parentContainsRelevant = true;
                            break;
                        }
                    } finally {
                        ancestryWalk.close();
                    }
                }
                if (!parentContainsRelevant) {
                    return parent.getName();
                }
                current = parent;
            }
            return null;
        } catch (IOException error) {
            throw new AppException("SEARCH_FAILED", "Failed to calculate release boundary.", 500);
        } finally {
            if (walk != null) {
                walk.close();
            }
            if (repository != null) {
                repository.close();
            }
        }
    }

    private List<CommitRecord> listFirstParentCommits(RevWalk walk, RevCommit start, GitLogOptions options, Date dateFrom, Date dateTo) throws IOException {
        List<CommitRecord> result = new ArrayList<CommitRecord>();
        RevCommit current = start;
        while (current != null && result.size() < options.scanLimit) {
            if (isVisible(current, options, dateFrom, dateTo)) {
                result.add(toCommitRecord(current));
            }
            if (current.getParentCount() == 0) {
                current = null;
            } else {
                current = walk.parseCommit(current.getParent(0).getId());
            }
        }
        return result;
    }

    private boolean isVisible(RevCommit commit, GitLogOptions options, Date dateFrom, Date dateTo) {
        if (!options.includeMerges && commit.getParentCount() > 1) {
            return false;
        }
        Date authoredAt = commit.getAuthorIdent().getWhen();
        if (dateFrom != null && authoredAt.before(dateFrom)) {
            return false;
        }
        return dateTo == null || !authoredAt.after(dateTo);
    }

    private RevCommit readCommit(Repository repository, RevWalk walk, String ref) throws IOException {
        ObjectId objectId = repository.resolve(ref + "^{commit}");
        if (objectId == null) {
            objectId = repository.resolve(ref);
        }
        if (objectId == null) {
            throw new AppException("REF_NOT_FOUND", "Requested branch or ref was not found.", 404);
        }
        return walk.parseCommit(objectId);
    }

    private CommitRecord toCommitRecord(RevCommit commit) {
        PersonIdent author = commit.getAuthorIdent();
        return new CommitRecord(
                commit.getName(),
                CoreUtils.safe(commit.getShortMessage()),
                readBody(commit),
                CoreUtils.safe(author.getName()),
                formatAuthoredAt(author)
        );
    }

    private String readBody(RevCommit commit) {
        String fullMessage = CoreUtils.safe(commit.getFullMessage()).replace("\r\n", "\n");
        String subject = CoreUtils.safe(commit.getShortMessage());
        if (fullMessage.startsWith(subject)) {
            return fullMessage.substring(subject.length()).trim();
        }
        return "";
    }

    private String formatAuthoredAt(PersonIdent author) {
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX");
        formatter.setTimeZone(author.getTimeZone());
        return formatter.format(author.getWhen());
    }

    private Date parseDate(String value) {
        if (value == null) {
            return null;
        }
        try {
            return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").parse(value);
        } catch (ParseException error) {
            throw new AppException("SEARCH_FAILED", "Search date is invalid.", 400, error.getMessage());
        }
    }

    private AbstractTreeIterator oldTreeIterator(Repository repository, RevWalk walk, RevCommit commit) throws IOException {
        if (commit.getParentCount() == 0) {
            return new EmptyTreeIterator();
        }
        RevCommit parent = walk.parseCommit(commit.getParent(0).getId());
        return treeIterator(repository, parent.getTree());
    }

    private AbstractTreeIterator treeIterator(Repository repository, RevTree tree) throws IOException {
        CanonicalTreeParser parser = new CanonicalTreeParser();
        ObjectReader reader = repository.newObjectReader();
        try {
            parser.reset(reader, tree.getId());
        } finally {
            reader.close();
        }
        return parser;
    }
}

class GitLogOptions {
    String resolvedRef;
    boolean includeMerges;
    boolean firstParent;
    String dateFrom;
    String dateTo;
    int scanLimit;
}

class CommitRecord {
    final String sha;
    final String subject;
    final String body;
    final String authorName;
    final String authoredAt;

    CommitRecord(String sha, String subject, String body, String authorName, String authoredAt) {
        this.sha = sha;
        this.subject = subject;
        this.body = body;
        this.authorName = authorName;
        this.authoredAt = authoredAt;
    }
}
