package visualization;

import org.graphstream.graph.Edge;
import org.graphstream.graph.Node;
import org.graphstream.graph.implementations.SingleGraph;
import org.graphstream.ui.layout.LayoutRunner;
import org.graphstream.ui.spriteManager.Sprite;
import org.graphstream.ui.spriteManager.SpriteManager;
import org.graphstream.ui.swingViewer.ViewPanel;
import org.graphstream.ui.view.Viewer;
import query.QueryInfo;

import javax.swing.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

public class Visualization implements MouseListener {
    SingleGraph graph;
    Viewer viewer;
    SpriteManager spriteManager;
    TreeLayout layout;
    QueryInfo info;
    JLabel counterLabel;
    int iterationCounter;
    Map<String, Integer> numVisits;

    private final String stylesheet = "" +
            "sprite { " +
            " shape: flow; " +
            " size: 5px;" +
            " z-index: 0; " +
            " sprite-orientation: from;" +
            " fill-color: green;" +
            "} " +
            "" +
            "node {" +
            " size: 50px;" +
            " fill-color: white;" +
            " text-color: white;" +
            " text-style: bold;" +
            " text-padding: 2px;" +
            " text-size: 15px;" +
            " text-background-mode: rounded-box;" +
            " text-background-color: rgb(35, 47, 62);" +
            "}" +
            "" +
            "edge {" +
            "  arrow-shape: none;" +
            "}";

    public void init(QueryInfo info) {
        this.info = info;
        Logger.getLogger(LayoutRunner.class.getSimpleName()).setUseParentHandlers(false);

        System.setProperty("org.graphstream.ui.renderer",
                "org.graphstream.ui.j2dviewer.J2DGraphRenderer");
        graph = new SingleGraph("Join Order");
        viewer = graph.display(false);
        viewer.setCloseFramePolicy(Viewer.CloseFramePolicy.CLOSE_VIEWER);
        viewer.disableAutoLayout();

        layout = new TreeLayout(graph);

        ViewPanel view = viewer.getDefaultView();
        for (MouseListener listener : view.getMouseListeners()) {
            view.removeMouseListener(listener);
        }
        view.addMouseListener(this);

        iterationCounter = 0;
        counterLabel = new JLabel("Number of Iterations: " + iterationCounter);
        counterLabel.setBounds(25, 0, 200, 50);
        view.add(counterLabel);
        view.setLayout(null);

        spriteManager = new SpriteManager(graph);
        graph.setAttribute("ui.stylesheet", stylesheet);
        graph.setAttribute("ui.antialias");
        graph.setAttribute("ui.quality");
        addNode("root").addAttribute("ui.label", "Join");

        numVisits = new HashMap<>();
    }

    private Node addNode(String id) {
        Node node = graph.addNode(id);
        layout.nodeAdded(id);
        return node;
    }

    public Edge addEdge(String edgeId, String fromId, String toId,
                        boolean directed) {
        Edge edge = graph.addEdge(edgeId, fromId, toId, directed);
        layout.edgeAdded(edgeId, fromId, toId, directed);
        return edge;
    }

    private void updateCounterLabel() {
        iterationCounter++;
        counterLabel.setText("Number of Samples: " + iterationCounter);
    }

    public boolean createNodesSpriteIfNotPresent(int[] joinOrder) {
        String currentJoinNode = "";
        String previous = "root";
        boolean modified = false;
        for (int x : joinOrder) {
            String tableName = info.aliasToTable.get(info.aliases[x]);

            currentJoinNode += (char) (65 + x);
            if (graph.getNode(currentJoinNode) == null) {
                Node newNode = addNode(currentJoinNode);
                newNode.addAttribute("ui.label", tableName);
                Edge edge = addEdge(previous + "--" + currentJoinNode,
                        previous, currentJoinNode, true);
                Sprite sprite = spriteManager.addSprite("S#" + previous +
                        "--" + currentJoinNode);
                sprite.addAttribute("progress");
                sprite.attachToEdge(edge.getId());
                sprite.setPosition(0);
                modified = true;
            }
            previous = currentJoinNode;
        }
        return modified;
    }

    private void frameTimeDelay() {
        if (iterationCounter < 5) {
            sleep(2000);
        } else if (iterationCounter < 10) {
            sleep(1000);
        } else if (iterationCounter < 15) {
            sleep(500);
        } else if (iterationCounter < 50) {
            sleep(125);
        } else if (iterationCounter < 150) {
            sleep(35);
        } else if (iterationCounter < 500) {
            sleep(10);
        }
    }

    private void colorNode(String node) {
        int num = Math.min(numVisits.get(node), 10000);
        long gb = 235 -
                Math.round((Math.log10(num) / 4.0) * (235 - 77));
        String color = "rgb(255, " + gb + ", " + gb + ")";
        graph.getNode(node)
                .addAttribute("ui.style", "fill-color: " + color + ";");
    }

    public void update(int[] joinOrder, double reward, int[] tupleIndices,
                       int[] tableCardinality) {
        updateCounterLabel();
        for (Sprite sprite : spriteManager.sprites()) {
            if (sprite.hasAttribute("progress")) {
                sprite.setAttribute("ui.hide");
            }
        }

        if (createNodesSpriteIfNotPresent(joinOrder)) {
            layout.compute();
        }

        String currentJoinNode = "";
        String previous = "root";
        for (int currentTable : joinOrder) {
            currentJoinNode += (char) (65 + currentTable);
            String spriteId = "S#" + previous + "--" + currentJoinNode;
            Sprite sprite = spriteManager.getSprite(spriteId);
            sprite.removeAttribute("ui.hide");
            sprite.setPosition(tupleIndices[currentTable] /
                    (double) tableCardinality[currentTable]);
            numVisits.put(currentJoinNode,
                    1 + (numVisits.containsKey(currentJoinNode) ?
                            numVisits.get(currentJoinNode) : 0));
            colorNode(currentJoinNode);
            previous = currentJoinNode;
        }

        frameTimeDelay();
    }

    @Override
    public void mouseClicked(MouseEvent e) {}

    @Override
    public void mousePressed(MouseEvent e) {}

    @Override
    public void mouseReleased(MouseEvent e) {}

    @Override
    public void mouseEntered(MouseEvent e) {}

    @Override
    public void mouseExited(MouseEvent e) {}

    private void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {}
    }
}