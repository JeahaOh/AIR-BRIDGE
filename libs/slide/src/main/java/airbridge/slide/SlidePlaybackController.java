package airbridge.slide;

final class SlidePlaybackController {
    enum AdvanceResult {
        SHOW_IMAGE,
        POST_FINISH
    }

    private int imageCount;
    private int currentIndex;
    private int completedLoops;
    private int lastNonBlackIndex = -1;
    private boolean showingBlackFrame;
    private boolean postFinishBlackout;

    void reset(int imageCount) {
        this.imageCount = Math.max(0, imageCount);
        currentIndex = 0;
        completedLoops = 0;
        lastNonBlackIndex = -1;
        showingBlackFrame = false;
        postFinishBlackout = false;
    }

    void startPlayback() {
        postFinishBlackout = false;
    }

    void pausePlayback() {
        if (!postFinishBlackout) {
            showingBlackFrame = false;
        }
    }

    AdvanceResult advanceToNext(int loopLimit) {
        showingBlackFrame = false;
        if (imageCount <= 0) {
            currentIndex = 0;
            return AdvanceResult.SHOW_IMAGE;
        }

        currentIndex++;
        if (currentIndex < imageCount) {
            return AdvanceResult.SHOW_IMAGE;
        }

        completedLoops++;
        if (loopLimit > 0 && completedLoops >= loopLimit) {
            currentIndex = imageCount - 1;
            postFinishBlackout = true;
            return AdvanceResult.POST_FINISH;
        }

        currentIndex = 0;
        return AdvanceResult.SHOW_IMAGE;
    }

    boolean navigateRelative(int delta) {
        if (imageCount <= 0) {
            return false;
        }
        boolean wasShowingBlackFrame = showingBlackFrame;
        postFinishBlackout = false;
        int targetIndex = Math.max(0, Math.min(imageCount - 1, currentIndex + delta));
        if (targetIndex == currentIndex && !wasShowingBlackFrame) {
            return false;
        }
        showingBlackFrame = false;
        currentIndex = targetIndex;
        return true;
    }

    boolean selectIndex(int index) {
        if (index < 0 || index >= imageCount) {
            return false;
        }
        currentIndex = index;
        return true;
    }

    void markCurrentImageShown() {
        showingBlackFrame = false;
        lastNonBlackIndex = currentIndex;
    }

    void showBlackFrame() {
        showingBlackFrame = true;
    }

    void enterPostFinishBlackout() {
        postFinishBlackout = true;
        showBlackFrame();
    }

    int currentIndex() {
        return currentIndex;
    }

    int completedLoops() {
        return completedLoops;
    }

    int lastNonBlackIndex() {
        return lastNonBlackIndex;
    }

    boolean showingBlackFrame() {
        return showingBlackFrame;
    }

    boolean postFinishBlackout() {
        return postFinishBlackout;
    }
}
