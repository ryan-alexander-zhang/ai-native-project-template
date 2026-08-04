package com.example.samples.s28.reconciliation.infrastructure;

import com.example.samples.s28.reconciliation.application.ArtifactStore;
import com.example.samples.s28.reconciliation.application.ExportSettings;
import com.example.samples.s28.reconciliation.domain.Artifact;
import com.example.samples.s28.reconciliation.domain.ExportJobId;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.stereotype.Component;

/**
 * Artifacts as files in a directory, which is honest for one instance and wrong for two.
 *
 * <p>Said plainly because it is the most likely thing to be copied out of this sample without noticing: a second
 * replica cannot serve a download for a file the first one wrote. Object storage is the answer, and the port is
 * shaped so that swapping it changes only this class — {@code Artifact} carries an opaque path, and nothing above
 * this layer opens a file.
 *
 * <p>What the sample does get right, and what is worth copying: the draft is written under a temporary name and
 * renamed into place atomically. A run that dies leaves {@code <id>.part}, which nothing looks for, rather than a
 * truncated {@code <id>.csv}, which everything does. A half-written reconciliation file is the worst of the three
 * possible outcomes because it is the only one that looks fine.
 */
@Component
class FileArtifactStore implements ArtifactStore {

  private final Path directory;

  FileArtifactStore(ExportSettings settings) {
    this.directory = Path.of(settings.getArtifactDir());
    try {
      Files.createDirectories(directory);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot create the artifact directory " + directory, e);
    }
  }

  @Override
  public Draft begin(ExportJobId id) {
    return new FileDraft(directory, id);
  }

  @Override
  public InputStream open(Artifact artifact) {
    try {
      return Files.newInputStream(Path.of(artifact.path()));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read artifact " + artifact.path(), e);
    }
  }

  @Override
  public void discard(Artifact artifact) {
    try {
      Files.deleteIfExists(Path.of(artifact.path()));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot delete artifact " + artifact.path(), e);
    }
  }

  private static final class FileDraft implements Draft {

    private final Path partial;
    private final Path finished;
    private final BufferedWriter writer;
    private boolean committed;
    private boolean closed;

    private FileDraft(Path directory, ExportJobId id) {
      this.partial = directory.resolve(id.value() + ".part");
      this.finished = directory.resolve(id.value() + ".csv");
      try {
        this.writer = Files.newBufferedWriter(partial, StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new UncheckedIOException("cannot open a draft for export " + id, e);
      }
    }

    @Override
    public void writeLine(String line) {
      try {
        writer.write(line);
        writer.write('\n');
      } catch (IOException e) {
        throw new UncheckedIOException("cannot write to " + partial, e);
      }
    }

    @Override
    public Artifact commit(long rowCount) {
      try {
        writer.close();
        Files.move(
            partial,
            finished,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
        committed = true;
        closed = true;
        return new Artifact(finished.toString(), Files.size(finished), rowCount);
      } catch (IOException e) {
        throw new UncheckedIOException("cannot publish " + finished, e);
      }
    }

    @Override
    public void abort() {
      if (closed) {
        return;
      }
      closed = true;
      try {
        writer.close();
      } catch (IOException ignored) {
        // Losing the writer's last buffer does not matter: the file is about to be deleted.
      }
      try {
        Files.deleteIfExists(partial);
      } catch (IOException e) {
        throw new UncheckedIOException("cannot remove the abandoned draft " + partial, e);
      }
    }

    @Override
    public void close() {
      if (!committed) {
        abort();
      }
    }
  }
}
