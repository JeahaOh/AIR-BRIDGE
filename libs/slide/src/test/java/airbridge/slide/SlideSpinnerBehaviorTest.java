package airbridge.slide;

import org.junit.jupiter.api.Test;

import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.text.NumberFormatter;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlideSpinnerBehaviorTest {
    @Test
    void defaultsUseFastPlaybackTiming() {
        assertEquals(80, SlideDefaults.DEFAULT_PAGE_DISPLAY_MS);
        assertEquals(10, SlideDefaults.DEFAULT_BLACK_FRAME_MS);
    }

    @Test
    void spinnerBoundsAllowFastPlayback() {
        // 고속 재생을 위해 Page 하한은 20ms, Black 하한은 1ms까지 허용한다.
        assertEquals(20, SlideDefaults.MIN_PAGE_DISPLAY_MS);
        assertEquals(1, SlideDefaults.MIN_BLACK_FRAME_MS);
    }

    @Test
    void pageSpinnerEditorEnforcesNewLowerBound() throws Exception {
        AtomicReference<AssertionError> failure = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> {
            try {
                JSpinner spinner = new JSpinner(new SpinnerNumberModel(SlideDefaults.DEFAULT_PAGE_DISPLAY_MS,
                        SlideDefaults.MIN_PAGE_DISPLAY_MS, SlideDefaults.MAX_PAGE_DISPLAY_MS,
                        SlideDefaults.PAGE_DISPLAY_STEP_MS));
                JSpinner.DefaultEditor editor = (JSpinner.DefaultEditor) spinner.getEditor();

                SlideApp.configureNumericSpinnerEditor(spinner, editor);

                NumberFormatter formatter = assertInstanceOf(NumberFormatter.class, editor.getTextField().getFormatter());
                assertEquals(20, formatter.getMinimum());
                assertEquals(10_000, formatter.getMaximum());
            } catch (AssertionError e) {
                failure.set(e);
            }
        });

        if (failure.get() != null) {
            throw failure.get();
        }
    }

    @Test
    void configureNumericSpinnerEditorRestrictsInvalidInput() throws Exception {
        AtomicReference<AssertionError> failure = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> {
            try {
                JSpinner spinner = new JSpinner(new SpinnerNumberModel(400, 50, 10_000, 50));
                JSpinner.DefaultEditor editor = (JSpinner.DefaultEditor) spinner.getEditor();

                SlideApp.configureNumericSpinnerEditor(spinner, editor);

                NumberFormatter formatter = assertInstanceOf(NumberFormatter.class, editor.getTextField().getFormatter());
                assertFalse(formatter.getAllowsInvalid());
                assertTrue(formatter.getCommitsOnValidEdit());
                assertEquals(50, formatter.getMinimum());
                assertEquals(10_000, formatter.getMaximum());
            } catch (AssertionError e) {
                failure.set(e);
            }
        });

        if (failure.get() != null) {
            throw failure.get();
        }
    }
}
