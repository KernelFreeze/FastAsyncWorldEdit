package com.boydti.fawe.beta.implementation;

import com.boydti.fawe.beta.ChunkFilterBlock;
import com.boydti.fawe.beta.Filter;
import com.boydti.fawe.beta.IChunk;
import com.boydti.fawe.beta.IQueueExtent;
import com.boydti.fawe.beta.filters.CountFilter;
import com.boydti.fawe.beta.filters.DistrFilter;
import com.boydti.fawe.config.Settings;
import com.boydti.fawe.object.changeset.FaweChangeSet;
import com.boydti.fawe.object.clipboard.WorldCopyClipboard;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.extent.PassthroughExtent;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.function.mask.BlockMask;
import com.sk89q.worldedit.function.mask.ExistingBlockMask;
import com.sk89q.worldedit.function.mask.Mask;
import com.sk89q.worldedit.function.pattern.BlockPattern;
import com.sk89q.worldedit.function.pattern.Pattern;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.util.Countable;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import com.sk89q.worldedit.world.block.BlockType;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ForkJoinTask;
import java.util.stream.IntStream;

public class ParallelQueueExtent extends PassthroughExtent implements IQueueWrapper {

    private final World world;
    private final QueueHandler handler;
    private final BatchProcessorHolder processor;

    public ParallelQueueExtent(QueueHandler handler, World world) {
        super(handler.getQueue(world, new BatchProcessorHolder()));
        this.world = world;
        this.handler = handler;
        this.processor = (BatchProcessorHolder) getExtent().getProcessor();
    }

    @Override
    public IQueueExtent getExtent() {
        return (IQueueExtent) super.getExtent();
    }

    private IQueueExtent getNewQueue() {
        return wrapQueue(handler.getQueue(this.world, this.processor));
    }

    @Override
    public IQueueExtent wrapQueue(IQueueExtent queue) {
        // TODO wrap
        queue.setProcessor(this.processor);
        return queue;
    }

    @Override
    public Extent enableHistory(FaweChangeSet changeSet) {
        return super.enableHistory(changeSet);
    }

    private ChunkFilterBlock apply(ChunkFilterBlock block, Filter filter, IQueueExtent queue, Region region, int X, int Z) {
        if (!filter.appliesChunk(X, Z)) {
            return block;
        }
        IChunk chunk = queue.getOrCreateChunk(X, Z);
        // Initialize
        chunk.init(queue, X, Z);

        IChunk newChunk = filter.applyChunk(chunk, region);
        if (newChunk != null) {
            chunk = newChunk;
            if (block == null) {
                block = queue.initFilterBlock();
            }
            chunk.filterBlocks(filter, block, region);
        }
        queue.submit(chunk);
        return block;
    }

    public <T extends Filter> T apply(Region region, T filter) {
        // The chunks positions to iterate over
        final Set<BlockVector2> chunks = region.getChunks();
        final Iterator<BlockVector2> chunksIter = chunks.iterator();

        // Get a pool, to operate on the chunks in parallel
        final int size = Math.min(chunks.size(), Settings.IMP.QUEUE.PARALLEL_THREADS);
        if (size <= 1) {
            BlockVector2 pos = chunksIter.next();
            apply(null, filter, getExtent(), region, pos.getX(), pos.getZ());
        } else {
            final ForkJoinTask[] tasks = IntStream.range(0, size).mapToObj(i -> handler.submit(() -> {
                try {
                    final Filter newFilter = filter.fork();
                    // Create a chunk that we will reuse/reset for each operation
                    final IQueueExtent queue = getNewQueue();
                    synchronized (queue) {
                        ChunkFilterBlock block = null;

                        while (true) {
                            // Get the next chunk posWeakChunk
                            final int X, Z;
                            synchronized (chunksIter) {
                                if (!chunksIter.hasNext()) {
                                    break;
                                }
                                final BlockVector2 pos = chunksIter.next();
                                X = pos.getX();
                                Z = pos.getZ();
                            }
                            block = apply(block, newFilter, queue, region, X, Z);
                        }
                        queue.flush();
                    }
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            })).toArray(ForkJoinTask[]::new);
            // Join filters
            for (ForkJoinTask task : tasks) {
                if (task != null) {
                    task.quietlyJoin();
                }
            }
            filter.join();
        }
        return filter;
    }

    public int getChanges() {
        return -1;
    }

    @Override
    public int countBlocks(Region region, Mask searchMask) {
        return
            // Apply a filter over a region
            apply(region, searchMask
                .toFilter(new CountFilter())) // Adapt the mask to a filter which counts
                .getParent() // Get the counter of this mask
                .getTotal(); // Get the total from the counter
    }

    @Override
    public <B extends BlockStateHolder<B>> int setBlocks(Region region, B block) throws MaxChangedBlocksException {
        apply(region, block);
        return getChanges();
    }

    @Override
    public int setBlocks(Region region, Pattern pattern) throws MaxChangedBlocksException {
        apply(region, pattern);
        return getChanges();
    }

    @Override
    public int setBlocks(Set<BlockVector3> vset, Pattern pattern) {
        if (vset instanceof Region) {
            setBlocks((Region) vset, pattern);
        }
        for (BlockVector3 blockVector3 : vset) {
            pattern.apply(this, blockVector3, blockVector3);
        }
        return getChanges();
    }

    @Override
    public int replaceBlocks(Region region, Mask mask, Pattern pattern)
        throws MaxChangedBlocksException {
        apply(region, mask.toFilter(pattern));
        return getChanges();
    }

    @Override
    public List<Countable<BlockState>> getBlockDistributionWithData(Region region) {
        return apply(region, new DistrFilter()).getDistribution();
    }

    @Override
    public List<Countable<BlockType>> getBlockDistribution(Region region) {
        return apply(region, new DistrFilter()).getTypeDistribution();
    }

    /**
     * To optimize
     */

    /**
     * Lazily copy a region
     *
     * @param region
     * @return
     */
    @Override
    public BlockArrayClipboard lazyCopy(Region region) {
        WorldCopyClipboard faweClipboard = new WorldCopyClipboard(this, region);
        BlockArrayClipboard weClipboard = new BlockArrayClipboard(region, faweClipboard);
        weClipboard.setOrigin(region.getMinimumPoint());
        return weClipboard;
    }

    /**
     * Count the number of blocks of a list of types in a region.
     *
     * @param region the region
     * @param searchBlocks the list of blocks to search
     * @return the number of blocks that matched the block
     */
    @Override
    public int countBlocks(Region region, Set<BaseBlock> searchBlocks) {
        BlockMask mask = new BlockMask(this, searchBlocks);
        return countBlocks(region, mask);
    }

    /**
     * Replaces all the blocks matching a given filter, within a given region, to a block
     * returned by a given pattern.
     *
     * @param region the region to replace the blocks within
     * @param filter a list of block types to match, or null to use {@link ExistingBlockMask}
     * @param replacement the replacement block
     * @return number of blocks affected
     * @throws MaxChangedBlocksException thrown if too many blocks are changed
     */
    @Override
    public <B extends BlockStateHolder<B>> int replaceBlocks(Region region, Set<BaseBlock> filter, B replacement) throws MaxChangedBlocksException {
        return replaceBlocks(region, filter, new BlockPattern(replacement));
    }

    /**
     * Replaces all the blocks matching a given filter, within a given region, to a block
     * returned by a given pattern.
     *
     * @param region the region to replace the blocks within
     * @param filter a list of block types to match, or null to use {@link ExistingBlockMask}
     * @param pattern the pattern that provides the new blocks
     * @return number of blocks affected
     * @throws MaxChangedBlocksException thrown if too many blocks are changed
     */
    @Override
    public int replaceBlocks(Region region, Set<BaseBlock> filter, Pattern pattern) throws MaxChangedBlocksException {
        Mask mask = filter == null ? new ExistingBlockMask(this) : new BlockMask(this, filter);
        return replaceBlocks(region, mask, pattern);
    }


    /*
    Don't need to optimize these
     */

//    /**
//     * Sets the blocks at the center of the given region to the given pattern.
//     * If the center sits between two blocks on a certain axis, then two blocks
//     * will be placed to mark the center.
//     *
//     * @param region the region to find the center of
//     * @param pattern the replacement pattern
//     * @return the number of blocks placed
//     * @throws MaxChangedBlocksException thrown if too many blocks are changed
//     */
//    @Override
//    public int center(Region region, Pattern pattern) throws MaxChangedBlocksException {
//        checkNotNull(region);
//        checkNotNull(pattern);
//
//        Vector3 center = region.getCenter();
//        Region centerRegion = new CuboidRegion(
//                this instanceof World ? (World) this : null, // Causes clamping of Y range
//                BlockVector3.at(((int) center.getX()), ((int) center.getY()), ((int) center.getZ())),
//                BlockVector3.at(MathUtils.roundHalfUp(center.getX()),
//                        center.getY(), MathUtils.roundHalfUp(center.getZ())));
//        return setBlocks(centerRegion, pattern);
//    }
}
