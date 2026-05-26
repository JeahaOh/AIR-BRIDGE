package airbridge.slide;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlidePlaybackControllerTest {
    @Test
    void advanceMovesWithinCurrentLoop() {
        SlidePlaybackController controller = new SlidePlaybackController();
        controller.reset(3);

        assertEquals(SlidePlaybackController.AdvanceResult.SHOW_IMAGE, controller.advanceToNext(1));
        assertEquals(1, controller.currentIndex());
        assertEquals(0, controller.completedLoops());

        assertEquals(SlidePlaybackController.AdvanceResult.SHOW_IMAGE, controller.advanceToNext(1));
        assertEquals(2, controller.currentIndex());
        assertEquals(0, controller.completedLoops());
    }

    @Test
    void advanceWrapsWhenLoopLimitIsUnlimited() {
        SlidePlaybackController controller = new SlidePlaybackController();
        controller.reset(2);

        controller.advanceToNext(0);
        assertEquals(SlidePlaybackController.AdvanceResult.SHOW_IMAGE, controller.advanceToNext(0));

        assertEquals(0, controller.currentIndex());
        assertEquals(1, controller.completedLoops());
        assertFalse(controller.postFinishBlackout());
    }

    @Test
    void advanceEntersPostFinishBlackoutWhenLoopLimitIsReached() {
        SlidePlaybackController controller = new SlidePlaybackController();
        controller.reset(2);

        controller.advanceToNext(1);
        assertEquals(SlidePlaybackController.AdvanceResult.POST_FINISH, controller.advanceToNext(1));

        assertEquals(1, controller.currentIndex());
        assertEquals(1, controller.completedLoops());
        assertTrue(controller.postFinishBlackout());
    }

    @Test
    void navigationClearsBlackFrameAndClampsToBounds() {
        SlidePlaybackController controller = new SlidePlaybackController();
        controller.reset(3);
        controller.advanceToNext(0);
        controller.showBlackFrame();

        assertTrue(controller.navigateRelative(10));
        assertEquals(2, controller.currentIndex());
        assertFalse(controller.showingBlackFrame());

        assertFalse(controller.navigateRelative(10));
        assertEquals(2, controller.currentIndex());
    }

    @Test
    void markCurrentImageShownTracksLastNonBlackIndex() {
        SlidePlaybackController controller = new SlidePlaybackController();
        controller.reset(2);

        controller.advanceToNext(0);
        controller.markCurrentImageShown();
        controller.showBlackFrame();

        assertEquals(1, controller.lastNonBlackIndex());
        assertTrue(controller.showingBlackFrame());
    }
}
