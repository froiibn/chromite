/*
 * Chromite - Database Workbench
 * Copyright (C) 2026 Chromite contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.seizia.chromite.ui.swing;

import org.jkiss.code.NotNull;

import java.util.*;

/**
 * ER 다이어그램 자동 배치: 관계로 이어진 테이블은 계층(층) 배치, 관계 없는 테이블은 격자.
 *
 * 설계: SWT의 자동 배치(GraphLayoutAuto, OrthoDirectedGraphLayout)는 draw2d의 그래프 배치에 묶여 있어 쓸 수 없다.
 * 같은 생각(관계 방향으로 층을 나눠 왼쪽에서 오른쪽으로)을 단순한 Sugiyama 방식으로 다시 구현했다.
 * 결과는 입력 순서(테이블 제목 순, {@link ErdDiagramModel})에만 달려 있어 같은 다이어그램은 언제나 같게 배치된다(난수 없음).
 * <ol>
 *     <li>관계로 이어진 묶음(연결 요소)을 나눈다. 관계가 없는 테이블은 마지막에 격자로 놓는다.</li>
 *     <li>묶음마다 층을 정한다: 참조되는 테이블(기본 키 쪽)이 왼쪽, 참조하는 테이블이 오른쪽이다. 층은 가장 긴 경로 길이이고,
 *     순환 참조는 이름 순 깊이 우선 탐색에서 되돌아가는 간선을 빼서 끊는다. 자기 참조는 무시한다.</li>
 *     <li>층 안의 순서는 이웃의 평균 위치(barycenter)로 위·아래 방향을 번갈아 몇 번 다시 정렬해 교차를 줄인다(같으면 이전 순서).</li>
 *     <li>좌표: 층마다 가장 넓은 테이블 폭만큼 열을 두고, 층 안에서는 위에서부터 쌓아 층들을 세로 가운데로 맞춘다.
 *     묶음들은 큰 것부터 선반(shelf) 방식으로 줄을 채워 놓는다.</li>
 * </ol>
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-erd.md)
 */
final class ErdLayout {

    static final int MARGIN = 40;
    private static final int LAYER_GAP = 90;
    private static final int NODE_GAP = 40;
    private static final int COMPONENT_GAP = 80;
    private static final int GRID_GAP = 40;
    private static final int ORDER_SWEEPS = 6;

    private ErdLayout() {
    }

    /**
     * Places all entities. Sizes must be measured.
     */
    static void layout(@NotNull List<ErdDiagramModel.Entity> entities, @NotNull List<ErdDiagramModel.Link> links) {
        int n = entities.size();
        if (n == 0) {
            return;
        }
        Map<ErdDiagramModel.Entity, Integer> index = new IdentityHashMap<>();
        for (int i = 0; i < n; i++) {
            index.put(entities.get(i), i);
        }
        // Directed edges parent (key side) -> child (foreign key side); undirected neighbors for the components
        List<Set<Integer>> children = new ArrayList<>();
        List<Set<Integer>> neighbors = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            children.add(new TreeSet<>());
            neighbors.add(new TreeSet<>());
        }
        for (ErdDiagramModel.Link link : links) {
            Integer s = index.get(link.source());
            Integer t = index.get(link.target());
            if (s == null || t == null || s.equals(t)) {
                continue;
            }
            children.get(t).add(s);
            neighbors.get(t).add(s);
            neighbors.get(s).add(t);
        }

        List<List<Integer>> components = new ArrayList<>();
        List<Integer> isolated = new ArrayList<>();
        boolean[] seen = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (seen[i]) {
                continue;
            }
            List<Integer> component = new ArrayList<>();
            Deque<Integer> queue = new ArrayDeque<>();
            queue.add(i);
            seen[i] = true;
            while (!queue.isEmpty()) {
                int v = queue.poll();
                component.add(v);
                for (int w : neighbors.get(v)) {
                    if (!seen[w]) {
                        seen[w] = true;
                        queue.add(w);
                    }
                }
            }
            if (component.size() == 1) {
                isolated.add(i);
            } else {
                Collections.sort(component);
                components.add(component);
            }
        }
        // Bigger groups first; equal sizes keep the order of their first table
        components.sort(Comparator.comparingInt((List<Integer> c) -> -c.size()).thenComparingInt(c -> c.getFirst()));

        List<Block> blocks = new ArrayList<>();
        for (List<Integer> component : components) {
            blocks.add(layoutComponent(entities, component, children));
        }
        if (!isolated.isEmpty()) {
            blocks.add(layoutGrid(entities, isolated));
        }
        placeBlocks(entities, blocks);
    }

    /**
     * Places the entities that have no position (new in the database since the layout was saved) to the right of the others,
     * stacked in a column.
     */
    static void placeNew(@NotNull List<ErdDiagramModel.Entity> entities) {
        double right = MARGIN - LAYER_GAP;
        for (ErdDiagramModel.Entity entity : entities) {
            if (entity.placed) {
                right = Math.max(right, entity.x + entity.width);
            }
        }
        double x = right + LAYER_GAP;
        double y = MARGIN;
        for (ErdDiagramModel.Entity entity : entities) {
            if (!entity.placed) {
                entity.x = x;
                entity.y = y;
                entity.placed = true;
                y += entity.height + NODE_GAP;
            }
        }
    }

    /**
     * Positions of a group relative to its top left corner.
     */
    private record Block(@NotNull List<Integer> nodes, double[] xs, double[] ys, double width, double height) {
    }

    @NotNull
    private static Block layoutComponent(@NotNull List<ErdDiagramModel.Entity> entities, @NotNull List<Integer> nodes,
                                         @NotNull List<Set<Integer>> children) {
        Set<Integer> members = new HashSet<>(nodes);
        // Acyclic edges: DFS in index order from the nodes, dropping edges back to a node on the stack
        Map<Integer, List<Integer>> dag = new HashMap<>();
        Map<Integer, Integer> state = new HashMap<>();
        for (int start : nodes) {
            if (!state.containsKey(start)) {
                dfs(start, children, members, state, dag);
            }
        }
        // Layer = longest path from a root, in topological order (reverse DFS finish order)
        Map<Integer, Integer> layer = new HashMap<>();
        List<Integer> topo = topologicalOrder(nodes, dag);
        for (int v : nodes) {
            layer.put(v, 0);
        }
        for (int v : topo) {
            for (int w : dag.getOrDefault(v, List.of())) {
                layer.put(w, Math.max(layer.get(w), layer.get(v) + 1));
            }
        }
        int layerCount = 0;
        for (int v : nodes) {
            layerCount = Math.max(layerCount, layer.get(v) + 1);
        }
        List<List<Integer>> layers = new ArrayList<>();
        for (int i = 0; i < layerCount; i++) {
            layers.add(new ArrayList<>());
        }
        for (int v : nodes) {
            layers.get(layer.get(v)).add(v);
        }

        // Undirected neighbors inside the group for the barycenter ordering
        Map<Integer, List<Integer>> adjacent = new HashMap<>();
        for (int v : nodes) {
            for (int w : children.get(v)) {
                if (members.contains(w) && w != v) {
                    adjacent.computeIfAbsent(v, k -> new ArrayList<>()).add(w);
                    adjacent.computeIfAbsent(w, k -> new ArrayList<>()).add(v);
                }
            }
        }
        Map<Integer, Double> position = new HashMap<>();
        for (List<Integer> l : layers) {
            for (int i = 0; i < l.size(); i++) {
                position.put(l.get(i), (double) i);
            }
        }
        for (int sweep = 0; sweep < ORDER_SWEEPS; sweep++) {
            boolean down = sweep % 2 == 0;
            for (int li = 0; li < layers.size(); li++) {
                int current = down ? li : layers.size() - 1 - li;
                int neighborLayer = down ? current - 1 : current + 1;
                List<Integer> l = layers.get(current);
                Map<Integer, Double> key = new HashMap<>();
                for (int v : l) {
                    double sum = 0;
                    int count = 0;
                    for (int w : adjacent.getOrDefault(v, List.of())) {
                        if (layer.get(w) == neighborLayer) {
                            sum += position.get(w);
                            count++;
                        }
                    }
                    key.put(v, count > 0 ? sum / count : position.get(v));
                }
                // Stable sort: ties keep the previous order
                l.sort(Comparator.comparingDouble(key::get));
                for (int i = 0; i < l.size(); i++) {
                    position.put(l.get(i), (double) i);
                }
            }
        }

        // Coordinates: columns by layer, stacks centered vertically
        double[] xs = new double[nodes.size()];
        double[] ys = new double[nodes.size()];
        Map<Integer, Integer> slot = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            slot.put(nodes.get(i), i);
        }
        double[] layerHeight = new double[layerCount];
        double maxHeight = 0;
        for (int li = 0; li < layerCount; li++) {
            double h = 0;
            for (int v : layers.get(li)) {
                h += entities.get(v).height;
            }
            h += NODE_GAP * Math.max(0, layers.get(li).size() - 1);
            layerHeight[li] = h;
            maxHeight = Math.max(maxHeight, h);
        }
        double x = 0;
        for (int li = 0; li < layerCount; li++) {
            double columnWidth = 0;
            double y = (maxHeight - layerHeight[li]) / 2;
            for (int v : layers.get(li)) {
                ErdDiagramModel.Entity entity = entities.get(v);
                xs[slot.get(v)] = x;
                ys[slot.get(v)] = Math.round(y);
                y += entity.height + NODE_GAP;
                columnWidth = Math.max(columnWidth, entity.width);
            }
            x += columnWidth + LAYER_GAP;
        }
        return new Block(nodes, xs, ys, x - LAYER_GAP, maxHeight);
    }

    private static void dfs(int v, @NotNull List<Set<Integer>> children, @NotNull Set<Integer> members,
                            @NotNull Map<Integer, Integer> state, @NotNull Map<Integer, List<Integer>> dag) {
        // Iterative DFS (deep FK chains must not overflow the stack); state 1 = on stack, 2 = done
        Deque<Iterator<Integer>> stack = new ArrayDeque<>();
        Deque<Integer> path = new ArrayDeque<>();
        state.put(v, 1);
        stack.push(children.get(v).iterator());
        path.push(v);
        while (!stack.isEmpty()) {
            Iterator<Integer> it = stack.peek();
            int from = path.peek();
            if (it.hasNext()) {
                int w = it.next();
                if (!members.contains(w) || w == from) {
                    continue;
                }
                Integer s = state.get(w);
                if (s == null) {
                    dag.computeIfAbsent(from, k -> new ArrayList<>()).add(w);
                    state.put(w, 1);
                    stack.push(children.get(w).iterator());
                    path.push(w);
                } else if (s == 2) {
                    // Cross or forward edge: keeps the graph acyclic
                    dag.computeIfAbsent(from, k -> new ArrayList<>()).add(w);
                }
                // s == 1: back edge of a cycle, dropped
            } else {
                stack.pop();
                state.put(path.pop(), 2);
            }
        }
    }

    @NotNull
    private static List<Integer> topologicalOrder(@NotNull List<Integer> nodes, @NotNull Map<Integer, List<Integer>> dag) {
        Map<Integer, Integer> inDegree = new HashMap<>();
        for (int v : nodes) {
            inDegree.putIfAbsent(v, 0);
            for (int w : dag.getOrDefault(v, List.of())) {
                inDegree.merge(w, 1, Integer::sum);
            }
        }
        // Lowest index first among the ready nodes: deterministic
        PriorityQueue<Integer> ready = new PriorityQueue<>();
        for (int v : nodes) {
            if (inDegree.get(v) == 0) {
                ready.add(v);
            }
        }
        List<Integer> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            int v = ready.poll();
            order.add(v);
            for (int w : dag.getOrDefault(v, List.of())) {
                if (inDegree.merge(w, -1, Integer::sum) == 0) {
                    ready.add(w);
                }
            }
        }
        return order;
    }

    @NotNull
    private static Block layoutGrid(@NotNull List<ErdDiagramModel.Entity> entities, @NotNull List<Integer> nodes) {
        int columns = (int) Math.ceil(Math.sqrt(nodes.size() * 1.5));
        double[] xs = new double[nodes.size()];
        double[] ys = new double[nodes.size()];
        double[] columnWidth = new double[columns];
        for (int i = 0; i < nodes.size(); i++) {
            columnWidth[i % columns] = Math.max(columnWidth[i % columns], entities.get(nodes.get(i)).width);
        }
        double y = 0;
        double width = 0;
        for (int row = 0; row * columns < nodes.size(); row++) {
            double x = 0;
            double rowHeight = 0;
            for (int c = 0; c < columns && row * columns + c < nodes.size(); c++) {
                int i = row * columns + c;
                xs[i] = x;
                ys[i] = y;
                rowHeight = Math.max(rowHeight, entities.get(nodes.get(i)).height);
                x += columnWidth[c] + GRID_GAP;
            }
            width = Math.max(width, x - GRID_GAP);
            y += rowHeight + GRID_GAP;
        }
        return new Block(nodes, xs, ys, width, y - GRID_GAP);
    }

    /**
     * Shelf packing of the groups: rows no wider than about a 4:3 page of the total area (at least the widest group).
     */
    private static void placeBlocks(@NotNull List<ErdDiagramModel.Entity> entities, @NotNull List<Block> blocks) {
        double area = 0;
        double widest = 0;
        for (Block block : blocks) {
            area += (block.width() + COMPONENT_GAP) * (block.height() + COMPONENT_GAP);
            widest = Math.max(widest, block.width());
        }
        double rowLimit = Math.max(widest, Math.sqrt(area * 4 / 3));
        double x = MARGIN;
        double y = MARGIN;
        double rowHeight = 0;
        for (Block block : blocks) {
            if (x > MARGIN && x + block.width() > MARGIN + rowLimit) {
                x = MARGIN;
                y += rowHeight + COMPONENT_GAP;
                rowHeight = 0;
            }
            for (int i = 0; i < block.nodes().size(); i++) {
                ErdDiagramModel.Entity entity = entities.get(block.nodes().get(i));
                entity.x = x + block.xs()[i];
                entity.y = y + block.ys()[i];
                entity.placed = true;
            }
            x += block.width() + COMPONENT_GAP;
            rowHeight = Math.max(rowHeight, block.height());
        }
    }
}
