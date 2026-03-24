/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2013 - 2014, AlgorithmX2, All rights reserved.
 *
 * Applied Energistics 2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Applied Energistics 2 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Applied Energistics 2.  If not, see <http://www.gnu.org/licenses/lgpl>.
 */

package appeng.crafting;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.inv.CraftingSimulationState;

/**
 * A crafting tree process is what represents a pattern in the crafting process. It has a parent node (its output), and
 * a list of child nodes for its inputs.
 */
public class CraftingTreeProcess {
    enum BatchingMode {
        NORMAL,
        EXACT_REUSABLE,
        LIMIT_QTY
    }

    record BatchAnalysis(boolean limitQty, Set<CraftingTreeNode> exactReusableNodes) {
    }

    private final CraftingTreeNode parent;
    final IPatternDetails details;
    private final CraftingCalculation job;
    // Use linked hashmap to ensure deterministic ordering of subcrafts
    private final Map<CraftingTreeNode, Long> nodes = new LinkedHashMap<>();
    boolean possible = true;
    private boolean containerItems;
    /**
     * If true, we perform this pattern by 1 at the time. This ensures that container items or outputs get reused when
     * possible.
     */
    private boolean limitQty;

    public CraftingTreeProcess(ICraftingService cc, CraftingCalculation job,
            IPatternDetails details,
            CraftingTreeNode craftingTreeNode) {
        this.parent = craftingTreeNode;
        this.details = details;
        this.job = job;

        updateLimitQty();

        final IPatternDetails.IInput[] inputs = this.details.getInputs();
        for (int x = 0; x < inputs.length; ++x) {
            var input = inputs[x];
            var firstInput = input.getPossibleInputs()[0];
            var node = new CraftingTreeNode(cc, job, firstInput.what(), firstInput.amount(), this, x);
            this.nodes.put(node, input.getMultiplier());
        }
    }

    /**
     * @see CraftingTreeNode#notRecursive
     */
    boolean notRecursive(IPatternDetails details) {
        return this.parent == null || this.parent.notRecursive(details);
    }

    /**
     * Check if this pattern has one of its outputs as input. If that's the case, update {@code limitQty} to make sure
     * we simulate this pattern one by one. Also check for container items.
     */
    private void updateLimitQty() {
        // TODO: consider checking substitute inputs as well?
        for (IPatternDetails.IInput input : details.getInputs()) {
            var primaryInput = input.getPossibleInputs()[0];
            boolean isAnInput = false;
            var remainder = input.getRemainingKey(primaryInput.what());

            for (var output : details.getOutputs()) {
                if (output.what().matches(primaryInput)) {
                    isAnInput = true;
                    break;
                }
            }

            if (isAnInput) {
                this.limitQty = true;
            }

            if (remainder != null) {
                this.containerItems = true;
            }
        }
    }

    BatchAnalysis analyzeBatching(CraftingSimulationState inv) {
        if (this.limitQty || !this.containerItems) {
            return new BatchAnalysis(this.limitQty, Set.of());
        }

        var exactReusableNodes = Collections.newSetFromMap(new IdentityHashMap<CraftingTreeNode, Boolean>());
        for (var node : this.nodes.keySet()) {
            switch (node.getBatchingMode(inv)) {
                case LIMIT_QTY -> {
                    return new BatchAnalysis(true, Set.of());
                }
                case EXACT_REUSABLE -> exactReusableNodes.add(node);
                case NORMAL -> {
                }
            }
        }

        return new BatchAnalysis(false, exactReusableNodes);
    }

    void request(CraftingSimulationState inv, long times)
            throws CraftBranchFailure, InterruptedException {
        this.job.handlePausing();
        request(inv, times, analyzeBatching(inv));
    }

    void request(CraftingSimulationState inv, long times, BatchAnalysis batchAnalysis)
            throws CraftBranchFailure, InterruptedException {
        boolean limitQty = batchAnalysis.limitQty();

        var containerItems = this.containerItems ? new KeyCounter() : null;

        // request and remove inputs...
        for (var entry : this.nodes.entrySet()) {
            long requestedAmount = entry.getValue() * times;
            if (!limitQty && batchAnalysis.exactReusableNodes().contains(entry.getKey())) {
                requestedAmount = entry.getValue();
            }
            entry.getKey().request(inv, requestedAmount, containerItems);
        }

        // by now we must have succeeded, otherwise an exception would have been thrown by request() above

        // add container items
        if (containerItems != null) {
            for (var stack : containerItems) {
                inv.insert(stack.getKey(), stack.getLongValue(), Actionable.MODULATE);
                inv.addStackBytes(stack.getKey(), stack.getLongValue(), 1);
            }
        }

        // add crafting results..
        for (var out : this.details.getOutputs()) {
            inv.insert(out.what(), out.amount() * times, Actionable.MODULATE);
        }

        inv.addCrafting(details, times);
        inv.addBytes(times);
    }

    long getNodeCount() {
        long tot = 0;

        for (CraftingTreeNode node : this.nodes.keySet()) {
            tot += node.getNodeCount();
        }

        return tot;
    }

    long getOutputCount(AEKey what) {
        long tot = 0;

        for (var is : this.details.getOutputs()) {
            if (what.matches(is)) {
                tot += is.amount();
            }
        }

        return tot;
    }

    boolean hasMultiplePaths() {
        for (var entry : nodes.entrySet()) {
            if (entry.getKey().hasMultiplePaths()) {
                return true;
            }
        }
        return false;
    }
}
