package biz.k11i.xgboost.gbm;

import biz.k11i.xgboost.tree.AbstractRegTree;
import biz.k11i.xgboost.tree.PreorderRegTree;
import biz.k11i.xgboost.util.FVec;
import biz.k11i.xgboost.util.ModelReader;

import java.io.IOException;
import java.io.Serializable;
import java.util.function.Function;

/**
 * Gradient boosted tree implementation.
 */
public class GBTree extends GBBase {
    private ModelParam mparam;
    private AbstractRegTree[] trees;
    private int[] tree_info;
    private Function<AbstractRegTree.Param, AbstractRegTree> treeCreationStrategy;

    private AbstractRegTree[][] _groupTrees;

    public GBTree() {
        this(param -> new PreorderRegTree());
    }

    public GBTree(Function<AbstractRegTree.Param, AbstractRegTree> treeCreationStrategy) {
        this.treeCreationStrategy = treeCreationStrategy;
    }

    @Override
    public void loadModel(ModelReader reader, boolean with_pbuffer) throws IOException {
        mparam = new ModelParam(reader);

        trees = new AbstractRegTree[mparam.num_trees];
        for (int i = 0; i < mparam.num_trees; i++) {
            AbstractRegTree.Param param = new AbstractRegTree.Param(reader);
            trees[i] = this.treeCreationStrategy.apply(param);
            trees[i].loadModel(param);
        }

        if (mparam.num_trees != 0) {
            tree_info = reader.readIntArray(mparam.num_trees);
        }

        if (mparam.num_pbuffer != 0 && with_pbuffer) {
            reader.skip(4 * mparam.predBufferSize());
            reader.skip(4 * mparam.predBufferSize());
        }

        _groupTrees = new AbstractRegTree[mparam.num_output_group][];
        for (int i = 0; i < mparam.num_output_group; i++) {
            int treeCount = 0;
            for (int j = 0; j < tree_info.length; j++) {
                if (tree_info[j] == i) {
                    treeCount++;
                }
            }

            _groupTrees[i] = new AbstractRegTree[treeCount];
            treeCount = 0;

            for (int j = 0; j < tree_info.length; j++) {
                if (tree_info[j] == i) {
                    _groupTrees[i][treeCount++] = trees[j];
                }
            }
        }
    }

    @Override
    public double[] predict(FVec feat, int ntree_limit) {
        double[] preds = new double[mparam.num_output_group];
        for (int gid = 0; gid < mparam.num_output_group; gid++) {
            preds[gid] = pred(feat, gid, ntree_limit);
        }
        return preds;
    }

    @Override
    public double predictSingle(FVec feat, int ntree_limit) {
        if (mparam.num_output_group != 1) {
            throw new IllegalStateException(
                    "Can't invoke predictSingle() because this model outputs multiple values: "
                    + mparam.num_output_group);
        }
        return pred(feat,  0, ntree_limit);
    }

    double pred(FVec feat, int bst_group, int ntree_limit) {
        AbstractRegTree[] trees = _groupTrees[bst_group];
        int treeleft = ntree_limit == 0 ? trees.length : ntree_limit;

        double psum = 0;
        for (int i = 0; i < treeleft; i++) {
            psum += trees[i].getLeafValue(feat);
        }

        return psum;
    }

    @Override
    public int[] predictLeaf(FVec feat, int ntree_limit) {
        return predPath(feat, ntree_limit);
    }


    int[] predPath(FVec feat, int ntree_limit) {
        int treeleft = ntree_limit == 0 ? trees.length : ntree_limit;

        int[] leafIndex = new int[treeleft];
        for (int i = 0; i < treeleft; i++) {
            leafIndex[i] = trees[i].getLeafIndex(feat);
        }
        return leafIndex;
    }


    static class ModelParam implements Serializable {
        /*! \brief number of trees */
        final int num_trees;
        /*! \brief number of root: default 0, means single tree */
        final int num_roots;
        /*! \brief number of features to be used by trees */
        final int num_feature;
        /*! \brief size of predicton buffer allocated used for buffering */
        final long num_pbuffer;
        /*!
         * \brief how many output group a single instance can produce
         *  this affects the behavior of number of output we have:
         *    suppose we have n instance and k group, output will be k*n
         */
        final int num_output_group;
        /*! \brief size of leaf vector needed in tree */
        final int size_leaf_vector;
        /*! \brief reserved parameters */
        final int[] reserved;

        ModelParam(ModelReader reader) throws IOException {
            num_trees = reader.readInt();
            num_roots = reader.readInt();
            num_feature = reader.readInt();
            reader.readInt(); // read padding
            num_pbuffer = reader.readLong();
            num_output_group = reader.readInt();
            size_leaf_vector = reader.readInt();
            reserved = reader.readIntArray(31);
            reader.readInt(); // read padding
        }

        long predBufferSize() {
            return num_output_group * num_pbuffer * (size_leaf_vector + 1);
        }
    }

}
