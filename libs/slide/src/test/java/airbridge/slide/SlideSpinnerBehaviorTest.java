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
