package se.deversity.asynctest.intellij;

import com.intellij.openapi.project.Project;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import se.deversity.asynctest.intellij.model.DetectorFinding;
import se.deversity.asynctest.intellij.model.JsonReportParser;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The main panel displayed in the async-test tool window.
 *
 * <p>Shows a two-row layout: a summary bar at the top and a sortable table of findings.
 * Clicking a row expands the full detector report in a detail pane below the table.
 */
final class FindingsPanel {

    private final Project project;
    private final JPanel root;
    private final FindingsTableModel tableModel;
    private final JBTable table;
    private final JTextArea detailArea;
    private final JLabel summaryLabel;

    FindingsPanel(Project project) {
        this.project = project;
        this.tableModel = new FindingsTableModel();

        root = new JPanel(new BorderLayout(0, 4));
        root.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        // Summary bar
        summaryLabel = new JLabel("No report loaded. Run tests with JsonReportListener registered.");
        summaryLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        root.add(summaryLabel, BorderLayout.NORTH);

        // Table
        table = new JBTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(22);
        table.getColumnModel().getColumn(0).setPreferredWidth(80);   // Severity
        table.getColumnModel().getColumn(1).setPreferredWidth(260);  // Detector
        table.getColumnModel().getColumn(2).setPreferredWidth(100);  // Time
        table.setDefaultRenderer(Object.class, new SeverityCellRenderer());

        // Detail pane
        detailArea = new JTextArea();
        detailArea.setEditable(false);
        detailArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        detailArea.setLineWrap(true);
        detailArea.setWrapStyleWord(true);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
            new JBScrollPane(table),
            new JBScrollPane(detailArea));
        split.setResizeWeight(0.6);
        root.add(split, BorderLayout.CENTER);

        // Click to expand detail
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = table.getSelectedRow();
                if (row >= 0 && row < tableModel.findings.size()) {
                    detailArea.setText(tableModel.findings.get(row).report);
                    detailArea.setCaretPosition(0);
                }
            }
        });
    }

    JComponent getComponent() {
        return root;
    }

    void refresh() {
        Path reportFile = locateReport();
        if (reportFile == null) {
            summaryLabel.setText("Report file not found. Check Settings → Tools → async-test.");
            tableModel.setFindings(List.of());
            return;
        }

        List<DetectorFinding> findings = JsonReportParser.parse(reportFile);
        tableModel.setFindings(findings);

        long critical = findings.stream().filter(f -> f.severity == DetectorFinding.Severity.CRITICAL).count();
        long high     = findings.stream().filter(f -> f.severity == DetectorFinding.Severity.HIGH).count();
        long medium   = findings.stream().filter(f -> f.severity == DetectorFinding.Severity.MEDIUM).count();
        long low      = findings.stream().filter(f -> f.severity == DetectorFinding.Severity.LOW).count();

        summaryLabel.setText(String.format(
            "%d finding(s) — %d CRITICAL, %d HIGH, %d MEDIUM, %d LOW  |  %s",
            findings.size(), critical, high, medium, low, reportFile));

        if (!findings.isEmpty()) {
            table.setRowSelectionInterval(0, 0);
            detailArea.setText(findings.get(0).report);
            detailArea.setCaretPosition(0);
        } else {
            detailArea.setText("");
        }
    }

    private Path locateReport() {
        String patterns = AsyncTestSettings.getInstance().getReportPathPattern();
        String basePath = project.getBasePath();
        if (basePath == null) return null;

        for (String pattern : patterns.split(",")) {
            pattern = pattern.trim();
            if (pattern.isEmpty()) continue;
            File candidate = new File(basePath, pattern);
            if (candidate.exists()) return candidate.toPath();
        }
        return null;
    }

    // ---- Table model ----

    private static final class FindingsTableModel extends AbstractTableModel {

        private static final String[] COLUMNS = {"Severity", "Detector", "Time"};
        private List<DetectorFinding> findings = new ArrayList<>();

        void setFindings(List<DetectorFinding> findings) {
            this.findings = new ArrayList<>(findings);
            fireTableDataChanged();
        }

        @Override public int getRowCount()    { return findings.size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int col) { return COLUMNS[col]; }

        @Override
        public Object getValueAt(int row, int col) {
            DetectorFinding f = findings.get(row);
            return switch (col) {
                case 0 -> f.severity.name();
                case 1 -> f.detectorName;
                case 2 -> f.timestampMs > 0
                    ? new java.util.Date(f.timestampMs).toString()
                    : "—";
                default -> "";
            };
        }
    }

    // ---- Severity-coloured cell renderer ----

    private static final class SeverityCellRenderer extends DefaultTableCellRenderer {

        @Override
        public Component getTableCellRendererComponent(
                JTable table, Object value, boolean isSelected,
                boolean hasFocus, int row, int column) {

            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            if (!isSelected && column == 0 && value instanceof String severity) {
                setForeground(switch (severity) {
                    case "CRITICAL" -> JBColor.RED;
                    case "HIGH"     -> new JBColor(new Color(200, 100, 0), new Color(255, 160, 60));
                    case "MEDIUM"   -> new JBColor(new Color(160, 120, 0), new Color(220, 200, 60));
                    case "LOW"      -> JBColor.GREEN.darker();
                    default         -> JBColor.GRAY;
                });
                setFont(getFont().deriveFont(Font.BOLD));
            } else if (!isSelected) {
                setForeground(table.getForeground());
                setFont(getFont().deriveFont(Font.PLAIN));
            }

            return this;
        }
    }
}
