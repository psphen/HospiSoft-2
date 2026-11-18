package com.hospisoft.export;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class FakeFileSaver implements FileSaver {

    public record Request(String suggestedFileName, ExportFormat format) {
    }

    private final List<Request> requests = new ArrayList<>();
    private Path answer;

    public FakeFileSaver(Path answer) {
        this.answer = answer;
    }

    public synchronized void answer(Path next) {
        this.answer = next;
    }

    @Override
    public synchronized Optional<Path> choose(String suggestedFileName, ExportFormat format) {
        requests.add(new Request(suggestedFileName, format));
        return Optional.ofNullable(answer);
    }

    public synchronized List<Request> requests() {
        return List.copyOf(requests);
    }

    public synchronized Request last() {
        return requests.get(requests.size() - 1);
    }
}
