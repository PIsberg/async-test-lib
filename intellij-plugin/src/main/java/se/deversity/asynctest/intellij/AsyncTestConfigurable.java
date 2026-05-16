package se.deversity.asynctest.intellij;

import com.intellij.openapi.options.Configurable;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * Settings panel shown under Settings → Tools → async-test.
 */
public final class AsyncTestConfigurable implements Configurable {

    private JTextField reportPathField;

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "async-test";
    }

    @Override
    public @Nullable JComponent createComponent() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);

        c.gridx = 0; c.gridy = 0; c.anchor = GridBagConstraints.WEST;
        panel.add(new JLabel("Report file paths (comma-separated):"), c);

        reportPathField = new JTextField(AsyncTestSettings.getInstance().getReportPathPattern(), 50);
        c.gridx = 0; c.gridy = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1.0;
        panel.add(reportPathField, c);

        c.gridx = 0; c.gridy = 2; c.fill = GridBagConstraints.NONE; c.weightx = 0;
        panel.add(new JLabel(
            "<html><small>Relative to the project root. The first file that exists is used.</small></html>"), c);

        // Push everything to top
        c.gridx = 0; c.gridy = 3; c.weighty = 1.0; c.fill = GridBagConstraints.VERTICAL;
        panel.add(new JPanel(), c);

        return panel;
    }

    @Override
    public boolean isModified() {
        return !reportPathField.getText()
            .equals(AsyncTestSettings.getInstance().getReportPathPattern());
    }

    @Override
    public void apply() {
        AsyncTestSettings.getInstance().setReportPathPattern(reportPathField.getText());
    }

    @Override
    public void reset() {
        reportPathField.setText(AsyncTestSettings.getInstance().getReportPathPattern());
    }
}
