package edu.neu.ccs.pyramid.classification.boosting.lktb;

import edu.neu.ccs.pyramid.classification.PriorProbClassifier;
import edu.neu.ccs.pyramid.dataset.*;
import edu.neu.ccs.pyramid.regression.ConstantRegressor;
import edu.neu.ccs.pyramid.regression.Regressor;
import edu.neu.ccs.pyramid.regression.regression_tree.LeafOutputCalculator;
import edu.neu.ccs.pyramid.regression.regression_tree.RegTreeConfig;
import edu.neu.ccs.pyramid.regression.regression_tree.RegTreeTrainer;
import edu.neu.ccs.pyramid.regression.regression_tree.RegressionTree;
import edu.neu.ccs.pyramid.util.MathUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.mahout.math.Vector;

import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * Created by chengli on 8/14/14.
 */
public class LKTBTrainer {
    private static final Logger logger = LogManager.getLogger();
    /**
     * F_k(x), used to speed up training.
     */
    private ScoreMatrix scoreMatrix;

    /**
     * p_k(x)
     */
    private ProbabilityMatrix probabilityMatrix;

    private LKTBConfig lktbConfig;

    /**
     * actually negative gradients, to be fit by the tree
     */
    private GradientMatrix gradientMatrix;
    private LKTreeBoost lkTreeBoost;


    public LKTBTrainer(LKTBConfig lktbConfig, LKTreeBoost lkTreeBoost){
        if (lktbConfig.getDataSet().getNumClasses()!=lkTreeBoost.getNumClasses()){
            throw new IllegalArgumentException("lktbConfig.getDataSet().getNumClasses()!=lkTreeBoost.getNumClasses()");
        }
        this.lktbConfig = lktbConfig;
        this.lkTreeBoost = lkTreeBoost;
        int numClasses = lkTreeBoost.getNumClasses();
        ClfDataSet dataSet= lktbConfig.getDataSet();
        int numDataPoints = dataSet.getNumDataPoints();
        this.scoreMatrix = new ScoreMatrix(numDataPoints,numClasses);
        //only add priors to empty models
        if (lktbConfig.usePrior() && lkTreeBoost.getRegressors(0).size()==0){
            setPriorProbs(dataSet);
        }
        this.initStagedScores();
        this.probabilityMatrix = new ProbabilityMatrix(numDataPoints,numClasses);
        this.updateProbabilityMatrix();
        this.gradientMatrix = new GradientMatrix(numDataPoints,numClasses, GradientMatrix.Objective.MAXIMIZE);
        this.updateGradientMatrix();

    }

    public void iterate(){
        int numClasses = lkTreeBoost.getNumClasses();
        for (int k=0;k<numClasses;k++){
            /**
             * parallel by feature
             */
            Regressor regressor = fitClassK(k);
            lkTreeBoost.addRegressor(regressor, k);
            /**
             * parallel by data
             */
            updateStagedScores(regressor, k);
        }

        /**
         * parallel by data
         */
        updateProbabilityMatrix();
        updateGradientMatrix();
    }

    public void setActiveFeatures(int[] activeFeatures) {
        this.lktbConfig.setActiveFeatures(activeFeatures);
    }

    public void setActiveDataPoints(int[] activeDataPoints) {
        this.lktbConfig.setActiveDataPoints(activeDataPoints);
    }

    public GradientMatrix getGradientMatrix() {
        return gradientMatrix;
    }

    public ProbabilityMatrix getProbabilityMatrix() {
        return probabilityMatrix;
    }



    //======================== PRIVATE ===============================================

    private void setPriorProbs(double[] probs){
        if (probs.length!=this.lkTreeBoost.getNumClasses()){
            throw new IllegalArgumentException("probs.length!=this.numClasses");
        }
        double average = Arrays.stream(probs).map(Math::log).average().getAsDouble();
        for (int k=0;k<this.lkTreeBoost.getNumClasses();k++){
            double score = Math.log(probs[k] - average);
            Regressor constant = new ConstantRegressor(score);
            lkTreeBoost.addRegressor(constant, k);
        }
    }

    /**
     * start with prior probabilities
     * should be called before setTrainConfig
     */
    private void setPriorProbs(ClfDataSet dataSet){
        PriorProbClassifier priorProbClassifier = new PriorProbClassifier(this.lkTreeBoost.getNumClasses());
        priorProbClassifier.fit(dataSet);
        double[] probs = priorProbClassifier.getClassProbs();
        this.setPriorProbs(probs);
    }


    /**
     * parallel by classes
     * calculate gradient vectors for all classes, store them
     */
    private void updateGradientMatrix(){
        int numDataPoints = this.lktbConfig.getDataSet().getNumDataPoints();
        IntStream.range(0, numDataPoints).parallel()
                .forEach(this::updateClassGradients);
    }


    private void initStagedScores(){
        int numClasses = this.lkTreeBoost.getNumClasses();
        for (int k=0;k<numClasses;k++){
            for (Regressor regressor: lkTreeBoost.getRegressors(k)){
                this.updateStagedScores(regressor,k);
            }
        }
    }

    private void updateClassGradients(int dataPoint){
        int numClasses = this.lkTreeBoost.getNumClasses();
        int label = this.lktbConfig.getDataSet().getLabels()[dataPoint];
        double[] probs = this.probabilityMatrix.getProbabilitiesForData(dataPoint);
        for (int k=0;k<numClasses;k++){
            double gradient;
            if (label==k){
                gradient = 1-probs[k];
            } else {
                gradient = 0-probs[k];
            }
            this.gradientMatrix.setGradient(dataPoint,k,gradient);
        }
    }


    /**
     * use scoreMatrix to update probabilities
     * numerically unstable if calculated directly
     * probability = exp(log(nominator)-log(denominator))
     */
    private void updateClassProb(int i){
        int numClasses = this.lkTreeBoost.getNumClasses();
        double[] scores = scoreMatrix.getScoresForData(i);

        double logDenominator = MathUtil.logSumExp(scores);
//        if (logger.isDebugEnabled()){
//            logger.debug("logDenominator for data point "+i+" with scores  = "+ Arrays.toString(scores)
//                    +" ="+logDenominator+", label = "+lktbConfig.getDataSet().getLabels()[i]);
//        }
        for (int k=0;k<numClasses;k++){
            double logNominator = scores[k];
            double pro = Math.exp(logNominator-logDenominator);
            this.probabilityMatrix.setProbability(i,k,pro);
            if (Double.isNaN(pro)){
                throw new RuntimeException("pro=NaN, logNominator = "
                        +logNominator+", logDenominator="+logDenominator+
                        ", scores = "+Arrays.toString(scores));

            }
        }
    }

    /**
     * parallel by data points
     * update scoreMatrix of class k
     * @param regressor
     * @param k
     */
    private void updateStagedScores(Regressor regressor, int k){
        ClfDataSet dataSet= this.lktbConfig.getDataSet();
        int numDataPoints = dataSet.getNumDataPoints();
        IntStream.range(0, numDataPoints).parallel()
                .forEach(dataIndex -> this.updateStagedScore(regressor,k,dataIndex));
    }

    /**
     * update one score
     * @param regressor
     * @param k class index
     * @param dataIndex
     */
    private void updateStagedScore(Regressor regressor, int k,
                                   int dataIndex){
        DataSet dataSet= this.lktbConfig.getDataSet();
        Vector vector = dataSet.getRow(dataIndex);
        double prediction = regressor.predict(vector);
        this.scoreMatrix.increment(dataIndex,k,prediction);
    }

    /**
     * use scoreMatrix to update probabilities
     * parallel by data
     */
    private void updateProbabilityMatrix(){
        ClfDataSet dataSet= this.lktbConfig.getDataSet();
        int numDataPoints = dataSet.getNumDataPoints();
        IntStream.range(0,numDataPoints).parallel()
                .forEach(this::updateClassProb);
    }

    /**
     * parallel
     * find the best regression tree for class k
     * apply newton step and learning rate
     * @param k class index
     * @return regressionTreeLk, shrunk
     * @throws Exception
     */
    private RegressionTree fitClassK(int k){
        double[] pseudoResponse = this.gradientMatrix.getGradientsForClass(k);
        int numClasses = this.lkTreeBoost.getNumClasses();
        double learningRate = this.lktbConfig.getLearningRate();

        LeafOutputCalculator leafOutputCalculator = probabilities -> {
            double nominator = 0;
            double denominator = 0;
            for (int i=0;i<probabilities.length;i++) {
                double label = pseudoResponse[i];
                nominator += label*probabilities[i];
                denominator += Math.abs(label) * (1 - Math.abs(label))*probabilities[i];
            }
            double out;
            if (denominator == 0) {
                out = 0;
            } else {
                out = ((numClasses - 1) * nominator) / (numClasses * denominator);
            }
            //protection from numerically unstable issue
            //todo does the threshold matter?
            if (out>1){
                out=1;
            }
            if (out<-1){
                out=-1;
            }
            if (Double.isNaN(out)) {
                throw new RuntimeException("leaf value is NaN");
            }
            if (Double.isInfinite(out)){
                throw new RuntimeException("leaf value is Infinite");
            }
            out *= learningRate;
            return out;
        };

        RegTreeConfig regTreeConfig = new RegTreeConfig();
        regTreeConfig.setMaxNumLeaves(this.lktbConfig.getNumLeaves());
        regTreeConfig.setMinDataPerLeaf(this.lktbConfig.getMinDataPerLeaf());
        regTreeConfig.setActiveDataPoints(this.lktbConfig.getActiveDataPoints());
        regTreeConfig.setActiveFeatures(this.lktbConfig.getActiveFeatures());
        regTreeConfig.setNumSplitIntervals(this.lktbConfig.getNumSplitIntervals());

        RegressionTree regressionTree = RegTreeTrainer.fit(regTreeConfig,
                this.lktbConfig.getDataSet(),
                pseudoResponse,
                leafOutputCalculator);
        return regressionTree;
    }



}
