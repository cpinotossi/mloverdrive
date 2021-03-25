package com.cpt.az;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.Integer.parseInt;

public final class Problem {

  private final int rowCount;
  private final int colCount;
  private final int nodeCount;

  private final Node[] nodes;

  private final double v0 = 10;
  private final double vn = 30;
  private final double s0 = 0;
  private final double sn = 1000;
  private final double hMax = 20;

  private final double M = 1_300_000;
  private final double A = 11495;
  private final double B = 1100.6D;
  private final double C = 86.55D;
  private final double g = 9.81D;
  private final double CR = 1.06D;
  private final double curvCoeff = 5_576_000;
  private final double virtCurv0 = 0.00021837D;
  private final double weight = 500_000;

  private final int maxTractionNotch = 8;
  private final int notchCount = maxTractionNotch + 1;
  private final int nSteps = 10;
  private final int nSections = nSteps - 1;

  private final double ds;
  private final double dist;
  private final double sigsig2;

  private final double[] shared;

  private final int pTractionWork;

  private StringBuffer outputMessage = new StringBuffer(128);

  public Problem(int rowCount, int colCount) {
    if (rowCount <= 1) {
      throw new IllegalArgumentException("rowCount: " + rowCount);
    }
    if (colCount < 1) {
      throw new IllegalArgumentException("colCount: " + colCount);
    }
    this.rowCount = rowCount;
    this.colCount = colCount;
    nodeCount = 2 + rowCount * colCount;
    nodes = new Node[nodeCount];
    dist = sn - s0;
    ds = dist / (colCount - 1);
    sigsig2 = dist * dist / 32;
    pTractionWork = nSteps * (notchCount + 1);
    shared = new double[pTractionWork + notchCount];

    int rows = rowCount;
    int cols = colCount;
    log("Initializing with rowCount={}, colCount={}", Integer.toString(rows), Integer.toString(cols));
    long start = System.nanoTime();
    buildGraph();
    int tBuildGraph = elapsedMillisSince(start);
    log("Graph generated in {} ms", Integer.toString(tBuildGraph));
    start = System.nanoTime();
    double norm = calculateNormalizedEdgeCosts();
    int tEdgeCostCalc = elapsedMillisSince(start);
    log("Edge cost calculation took {} ms", Integer.toString(tEdgeCostCalc));
    start = System.nanoTime();
    Solution solution = findShortestPath();
    int tDijkstra = elapsedMillisSince(start);
    log("Dijsktra was executed in {} ms", Integer.toString(tDijkstra));
    solution.norm = norm;
    printSolution(solution);
    log("Total runtime was {} ms", Integer.toString(tBuildGraph + tEdgeCostCalc + tDijkstra));
  }

  public String getTrashBack() {
    return outputMessage.toString();
  }

  public Solution solve() {
    buildGraph();
    double norm = calculateNormalizedEdgeCosts();
    Solution solution = findShortestPath();
    solution.norm = norm;
    return solution;
  }

  public void reset() {
    Arrays.fill(nodes, null);
    Arrays.fill(shared, 0D);
  }

  public void buildGraph() {
    double[] v = new double[rowCount];
    linspace(v, v0, vn);
    double[] vs = new double[rowCount];
    for (int i = 0; i < rowCount; ++i) {
      vs[i] = subs(v[i]);
    }
    double smu = (s0 - sn) / 2;
    double s = s0;
    double h;
    nodes[0] = createFirstNode();
    Node[] prev = new Node[rowCount];
    int iNode = 1;
    for (int x = 0; x < colCount; ++x) {
      h = hMax * Math.exp(-smu * smu / sigsig2);
      if (x == 0) {
        for (int j = 0; j < rowCount; ++j) {
          Node node = new Node();
          node.index = iNode;
          node.s = s;
          node.h = h;
          node.v = v[j];
          node.vs = vs[j];
          node.dest = new Node[rowCount];
          node.cost = new double[rowCount];
          node.distance = Double.MAX_VALUE;
          node.unvisited = 1;
          nodes[iNode++] = node;
          nodes[0].dest[j] = node;
          prev[j] = node;
        }
      } else {
        for (int j = 0; j < rowCount; ++j) {
          Node node = new Node();
          node.index = iNode;
          node.s = s;
          node.h = h;
          node.v = v[j];
          node.vs = vs[j];
          node.dest = new Node[rowCount];
          node.cost = new double[rowCount];
          node.distance = Double.MAX_VALUE;
          node.unvisited = rowCount;
          nodes[iNode++] = node;
          for (int i = 0; i < rowCount; ++i) {
            prev[i].dest[j] = node;
          }
        }
        for (int i = rowCount - 1, j = iNode - 1; i >= 0; --i, --j) {
          prev[i] = nodes[j];
        }
      }
      s += ds;
      smu += ds;
    }
    Node last = createLastNode();
    nodes[iNode++] = last;
    for (Node node : prev) {
      node.dest = new Node[1];
      node.dest[0] = last;
      node.cost = new double[1];
    }
  }

  private Node createFirstNode() {
    Node node = new Node();
    node.index = 0;
    node.h = 0D;
    node.s = s0;
    node.v = v0;
    node.vs = subs(v0);
    node.dest = new Node[rowCount];
    node.cost = new double[rowCount];
    for (int i = 0; i < rowCount; ++i) {
      node.cost[i] = i + 1;
    }
    return node;
  }

  private Node createLastNode() {
    Node node = new Node();
    node.index = nodeCount - 1;
    node.h = 0D;
    node.s = sn;
    node.v = vn;
    node.vs = subs(vn);
    node.dest = new Node[0];
    node.cost = new double[0];
    node.unvisited = rowCount;
    node.distance = Double.MAX_VALUE;
    return node;
  }

  public double calculateNormalizedEdgeCosts() {
    double min = Double.MAX_VALUE;
    double cost = 0D;
    for (Node src : nodes) {
      int ln = src.dest.length;
      if (src.index == 0 || ln == 1) {
        continue;
      }
      for (int i = 0; i < ln; ++i) {
        cost = calculateEdgeCost(src, src.dest[i]);
        src.cost[i] = cost;
        if (cost < min) {
          min = cost;
        }
      }
    }
    for (Node src : nodes) {
      int ln = src.cost.length;
      for (int i = 0; i < ln; ++i) {
        src.cost[i] -= min;
      }
    }
    return min;
  }

  private double calculateEdgeCost(Node src, Node dest) {
    // calculateTimeAndEnergy:
    double si = src.s;
    double sj = dest.s;
    double ds = sj - si;
    double vi = src.v;
    double viSafe = src.vs;
    double vj = dest.v;
    double vjSafe = dest.vs;
    double dv = vjSafe - viSafe;
    double dvAbs = Math.abs(dv);
    final double t;
    if (dvAbs < 0.00000001D) {
      t = ds / viSafe;
    } else {
      t = Math.log(vjSafe / viSafe) * ds / dv;
    }
    double eCurv = curvCoeff * ds * virtCurv0;
    double dh = dest.h - src.h;
    double ePot = M * g * dh;
    double vi2 = vi * vi;
    double eKin = 0.5D * CR * M * (vj * vj - vi2);
    final double w;
    if (vi == vj) {
      w = ePot + eCurv + (A + B * vi + C * vi2) * ds;
    } else {
      double alfa = dv / ds;
      double alfa2 = alfa * alfa;
      double k1 = B * (vi - alfa * si);
      double k2 = C * (vi2 + alfa2 * si * si - 2 * vi * alfa * si);
      double k3 = C * 2 * (vi * alfa - si * alfa2);
      double si2 = si * si;
      double sj2 = sj * sj;
      double eResist = (A + k1 + k2) * ds + //
          (B * alfa + k3) * (sj2 - si2) / 2 + //
          (C * alfa2) * (sj2 * sj - si2 * si) / 3;
      w = ePot + eCurv + eKin + eResist;
    }
    int p = 0;
    for (int i = 0; i < nSteps; ++i) {
      shared[p++] = ((nSections - i) * vi + i * vj) / nSections;
    }
    for (int i = 0; i <= maxTractionNotch;) {
      ++i;
      for (int j = 0; j < nSteps;) {
        shared[p++] = i * ++j;
      }
    }
    double fTraction = calculateForceAndNotch(ds, dvAbs, w, 0D);
    double epsilon = Math.abs(fTraction) < 1D ? fTraction : 1D / fTraction;
    return w + weight * t + epsilon;
  }

  private double calculateForceAndNotch(double ds, double dvAbs, double workRequired, double initialExtraWork) {
    int selectedNotch = maxTractionNotch;
    double threshold = 1e-8D;
    for (int notch = 1; notch <= maxTractionNotch; ++notch) {
      double work = calculateForceBaseAtNotch(notch) * ds / nSections;
      shared[pTractionWork + notch] = work;
      double deviation = initialExtraWork + work - workRequired;
      if (notch > 1 && deviation >= 0) {
        selectedNotch = deviation < threshold ? notch : notch - 1;
        break;
      }
      threshold = Math.min(1e-8D, Math.abs(deviation));
    }
    return shared[nSteps * (selectedNotch + 2) - 1];
  }

  private double calculateForceBaseAtNotch(int notch) {
    int p = nSteps * (notch + 1);
    return shared[p] / 2
        + shared[p + 1]
        + shared[p + 2]
        + shared[p + 3]
        + shared[p + 4]
        + shared[p + 5]
        + shared[p + 6]
        + shared[p + 7]
        + shared[p + 8]
        + shared[p + 9] / 2; // WARNING this method is unrolled with nSteps == 10;
  }

  public Solution findShortestPath() {
    LinkedList<Node> ready = new LinkedList<>();
    ready.add(nodes[0]);
    while (!ready.isEmpty()) {
      Node src = ready.removeFirst();
      double baseCost = src.distance;
      int ln = src.dest.length;
      for (int i = 0; i < ln; ++i) {
        Node dest = src.dest[i];
        double totalCost = baseCost + src.cost[i];
        if (totalCost < dest.distance) {
          dest.distance = totalCost;
          dest.prev = src;
        }
        if (--dest.unvisited == 0) {
          ready.add(dest);
        }
      }
    }
    Solution solution = new Solution();
    Node node = nodes[nodeCount - 1];
    if (node.prev == null) {
      throw new IllegalStateException("Did not reach the end of the Graph");
    }
    solution.cost = node.distance;
    node = node.prev;
    int[] path = new int[colCount];
    for (int i = colCount - 1; i >= 0; node = node.prev, --i) {
      path[i] = node.index;
    }
    solution.nodes = path;
    return solution;
  }

  private static void linspace(double[] array, double x, double y) {
    int last = array.length - 1;
    for (int i = last; i >= 0; --i) {
      array[i] = ((last - i) * x + i * y) / last;
    }
  }

  private static double subs(double in) {
    return Math.abs(in) < 1e-8D ? 1e-6D : in;
  }

  public void printSolution(Solution solution) {
//    log("Nodes traversed: {}", intsToString(solution.nodes));
    log("Shortest distance: {}", solution.cost);
    log("Denormalized cost was: {}", solution.calculateDenormalizedCost());
  }

  static int elapsedMillisSince(long startNanos) {
    return (int) ((System.nanoTime() - startNanos) / MS_IN_NANOS);
  }

  void log(String fmt, Object arg) {
    String msg = PTRN_ARG.matcher(fmt).replaceFirst(arg.toString());
    outputMessage.append(msg+"\n");
    System.out.println(msg);
  }

  void log(String fmt, Object arg1, Object arg2) {
    Matcher matcher = PTRN_ARG.matcher(fmt);
    StringBuffer buf = new StringBuffer(128);
    matcher.find();
    matcher.appendReplacement(buf, arg1.toString());
    matcher.find();
    matcher.appendReplacement(buf, arg2.toString());
    matcher.appendTail(buf);
    outputMessage.append(buf.toString() + "\n");
    System.out.println(buf.toString());
  }

  static String intsToString(int[] array) {
    int ln = array.length;
    StringBuilder builder = new StringBuilder(ln * 5);
    builder.append('[').append(array[0]);
    for (int i = 1; i < ln; i++) {
      builder.append(", ").append(array[i]);
    }
    return builder.append(']').toString();
  }

  private static final Pattern PTRN_ARG = Pattern.compile("{}", Pattern.LITERAL);

  private static final int MS_IN_NANOS = 1_000_000;

  public final class Solution {

    public int[] nodes;
    public double cost;
    public double norm;

    public double calculateDenormalizedCost() {
      return cost + norm * (nodes.length + 1);
    }

  }

}
