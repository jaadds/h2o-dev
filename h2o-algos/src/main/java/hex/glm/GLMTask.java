package hex.glm;

import hex.DataInfo;
import hex.DataInfo.Row;


import java.util.Arrays;

import hex.glm.GLMModel.GLMParameters;
import hex.gram.Gram;
import hex.glm.GLMModel.GLMParameters.Family;
import hex.glm.GLMValidation.GLMXValidation;
import hex.optimization.L_BFGS.GradientInfo;
import jsr166y.CountedCompleter;
import water.H2O.H2OCountedCompleter;
import water.*;
import water.fvec.Chunk;
import water.util.ArrayUtils;
import water.util.FrameUtils;
import water.util.Log;
import water.util.MathUtils;

/**
 * All GLM related distributed tasks:
 *
 * YMUTask           - computes response means on actual datasets (if some rows are ignored - e.g ignoring rows with NA and/or doing cross-validation)
 * GLMGradientTask   - computes gradient at given Beta, used by L-BFGS, for KKT condition check
 * GLMLineSearchTask - computes residual deviance(s) at given beta(s), used by line search (both L-BFGS and IRLSM)
 * GLMIterationTask  - used by IRLSM to compute Gram matrix and response t(X) W X, t(X)Wz
 *
 * @author tomasnykodym
 */
public abstract class GLMTask  {

 static class YMUTask extends MRTask<YMUTask> {
   double _ymu;
   double _yMin = Double.POSITIVE_INFINITY, _yMax = Double.NEGATIVE_INFINITY;
   long   _nobs;

   public YMUTask(DataInfo dinfo, H2OCountedCompleter cmp){super(cmp);}

   @Override public void map(Chunk [] chunks) {
     boolean [] skip = MemoryManager.mallocZ(chunks[0]._len);
     for(int i = 0; i < chunks.length-1; ++i) {
       for(int r = chunks[i].nextNZ(-1); r < chunks[i]._len; r = chunks[i].nextNZ(r)) {
         skip[r] |= chunks[i].isNA(r);
       }
     }
     Chunk response = chunks[chunks.length-1];
     for(int r = 0; r < response._len; ++r) {
       if(skip[r]) continue;
       if(!skip[r] && !response.isNA(r)) {
         double d = response.atd(r);
         assert !Double.isNaN(d);
         assert !Double.isNaN(_ymu+d):"got NaN by adding " + _ymu + " + " + d;
         _ymu += d;
         if(d < _yMin)
           _yMin = d;
         if(d > _yMax)
           _yMax = d;
         ++_nobs;
       }
     }
   }
   @Override public void postGlobal() { _ymu /= _nobs;}
   @Override public void reduce(YMUTask ymt) {
     if(_nobs > 0 && ymt._nobs > 0) {
       _ymu += ymt._ymu;
       _nobs += ymt._nobs;
       if(_yMin > ymt._yMin)
         _yMin = ymt._yMin;
       if(_yMax < ymt._yMax)
         _yMax = ymt._yMax;
     } else if (_nobs == 0) {
       _ymu = ymt._ymu;
       _nobs = ymt._nobs;
       _yMin = ymt._yMin;
       _yMax = ymt._yMax;
     }
   }
   public double ymu(int foldId) {return _ymu;  } // TODO add folds to support cross validation!
   public long  nobs(int foldId) {return _nobs; } // TODO add folds to support cross validation!
 }

  static class GLMLineSearchTask extends MRTask<GLMLineSearchTask> {
    final DataInfo _dinfo;
    final double [] _beta;
    final double [] _direction;
    final double _step;
    final int _nSteps;
    final GLMParameters _params;
    final double _reg;
    final double _lambda;
    final double _alpha;

    public GLMLineSearchTask(DataInfo dinfo, double alpha, double lambda, GLMParameters params, double reg, double [] beta, double [] direction, double step, int nsteps ){this(dinfo, alpha, lambda, params, reg, beta, direction, step, nsteps, null);}
    public GLMLineSearchTask(DataInfo dinfo, double alpha, double lamdba, GLMParameters params, double reg, double [] beta, double [] direction, double step, int nsteps, CountedCompleter cc) {
      super ((H2OCountedCompleter)cc);
      _dinfo = dinfo;
      _reg = reg;
      _alpha = alpha;
      _lambda = lamdba;
      _beta = beta;
      _direction = direction;
      _step = step;
      _nSteps = nsteps;
      _params = params;
    }

    double [] _objVals; // result

//    private final double beta(int i, int j) {
//      return _beta[j] + _direction[j] * _steps[i];
//    }
    // compute linear estimate by summing contributions for all columns
    // (looping by column in the outer loop to have good access pattern and to exploit sparsity)
    @Override
    public void map(Chunk [] chks) {
//      Chunk responseChunk = chks[chks.length-1];
//      boolean[] skip = MemoryManager.mallocZ(chks[0]._len);
//      double [][] eta = new double[_nSteps][];
//      for(int i = 0; i < eta.length; ++i)
//        eta[i] = MemoryManager.malloc8d(chks[0]._len);
//
//      // categoricals
//      for(int i = 0; i < _dinfo._cats; ++i) {
//        Chunk c = chks[i];
//        for(int r = 0; r < c._len; ++r) { // categoricals can not be sparse
//          if(skip[r] || c.isNA(r)) {
//            skip[r] = true;
//            continue;
//          }
//          int off = _dinfo.getCategoricalId(i,(int)c.at8(r));
//          if(off != -1)
//            for(int j = 0; j < eta.length; ++j)
//              eta[j][r] += beta(j,off);
//        }
//      }
//
//      // compute default eta offset for 0s
//      final int numStart = _dinfo.numStart();
//      if(_dinfo._normMul != null && _dinfo._normSub != null) {
//        for (int j = 0; j < eta.length; ++j) {
//          double off = 0;
//          for (int i = 0; i < _dinfo._nums; ++i)
//            off -= beta(j, numStart + i) * _dinfo._normSub[i] * _dinfo._normMul[i];
//          for (int r = 0; r < chks[0]._len; ++r)
//            eta[j][r] += off;
//        }
//      }
//      // non-zero numbers
//      for (int i = 0; i < _dinfo._nums; ++i) {
//        Chunk c = chks[i + _dinfo._cats];
//        for (int r = c.nextNZ(-1); r < c._len; r = c.nextNZ(r)) {
//          if(skip[r] || c.isNA(r)) {
//            skip[r] = true;
//            continue;
//          }
//          double d = c.atd(r);
//          if (_dinfo._normMul != null)
//            d *= _dinfo._normMul[i];
//          for (int j = 0; j < eta.length; ++j)
//            eta[j][r] += beta(j,numStart + i) * d;
//        }
//      }
//      _objVals = MemoryManager.malloc8d(_nSteps);
//      for(int r = 0; r < chks[0]._len; ++r){
//        if(skip[r] || responseChunk.isNA(r))
//          continue;
//        double off = 0; //(_dinfo._offset?offsetChunk.atd(r):0);
//        double y = responseChunk.atd(r);
//        double yy = -1 + 2*y;
//        for(int i = 0; i < eta.length; ++i) {
//          double offset = off + (_dinfo._intercept ? beta(i,_beta.length-1): 0);
////          if(_params._family == Family.binomial) {
////            _objVals[i] += Math.log(1 + Math.exp(-yy*(eta[i][r] + offset)));
////          } else {
//            double mu = _params.linkInv(eta[i][r] + offset);
//            _objVals[i] += _params.likelihood(y, eta[i][r] + offset, mu);
////          }
//        }
//      }

      Chunk responseChunk = chks[chks.length-1];
      boolean[] skip = MemoryManager.mallocZ(chks[0]._len);
      double [][] eta = new double[responseChunk._len][_nSteps];
      double [] beta = _beta;
      double [] pk = _direction;

      // categoricals
      for(int i = 0; i < _dinfo._cats; ++i) {
        Chunk c = chks[i];
        for(int r = 0; r < c._len; ++r) { // categoricals can not be sparse
          if(skip[r] || c.isNA(r)) {
            skip[r] = true;
            continue;
          }
          int off = _dinfo.getCategoricalId(i,(int)c.at8(r));
          if(off != -1) {
            double t = 1;
            for (int j = 0; j < _nSteps; ++j, t *= _step)
              eta[r][j] += beta[off] + pk[off] * t;
          }
        }
      }
      // compute default eta offset for 0s
      final int numStart = _dinfo.numStart();
      double [] off = new double[_nSteps];
      if(_dinfo._normMul != null && _dinfo._normSub != null) {

        for (int i = 0; i < _dinfo._nums; ++i) {
          double b = beta[numStart+i];
          double s = pk[numStart+i];
          double d = _dinfo._normSub[i] * _dinfo._normMul[i];
          for (int j = 0; j < _nSteps; ++j, s *= _step)
            off[j] -= (b + s) * d;
        }
      }
      // non-zero numbers
      for (int i = 0; i < _dinfo._nums; ++i) {
        Chunk c = chks[i + _dinfo._cats];
        for (int r = c.nextNZ(-1); r < c._len; r = c.nextNZ(r)) {
          if(skip[r] || c.isNA(r)) {
            skip[r] = true;
            continue;
          }
          double d = c.atd(r);
          if (_dinfo._normMul != null)
            d *= _dinfo._normMul[i];
          double b = beta[numStart+i];
          double s = pk[numStart+i];
          for (int j = 0; j < _nSteps; ++j) {
            eta[r][j] += (b + s) * d;
            s *= _step;
          }
        }
      }
      _objVals = MemoryManager.malloc8d(_nSteps);
      for(int r = 0; r < chks[0]._len; ++r){
        if(skip[r] || responseChunk.isNA(r))
          continue;

        double y = responseChunk.atd(r);
        double yy = -1 + 2*y;
        double b = 0, s = 0;
        if(_dinfo._intercept) {
          b = beta[beta.length-1];
          s = pk[pk.length-1];
        }
        for(int i = 0; i < _nSteps; ++i, s*= _step) {
          double e = eta[r][i] + off[i] + b + s;
          if(_params._family == Family.binomial) {
            _objVals[i] += Math.log(1 + Math.exp(-yy * e));
          } else {
            double mu = _params.linkInv(e);
            _objVals[i] += _params.likelihood(y, e, mu);
          }
        }
      }
    }
    @Override public void reduce(GLMLineSearchTask glt){
      ArrayUtils.add(_objVals,glt._objVals);
    }
    @Override public void postGlobal(){
      double l2pen = .5 * (1-_alpha)*_lambda;
      double l1pen =  _alpha*_lambda;
      int N = _beta.length - (_dinfo._intercept?1:0);
      double b2 = ArrayUtils.l2norm2(_beta,_dinfo._intercept);
      double d2 = ArrayUtils.l2norm2(_direction,_dinfo._intercept);
      double bd = 0;
      for(int i = 0; i < _beta.length - (_dinfo._intercept?1:0); ++i)
        bd += _beta[i] * _direction[i];
      double t = 1;
      for(int i = 0; i < _objVals.length; ++i) {
        double l1norm = 0;
        if(_alpha > 0) {
          for(int j = 0; j < N; ++j){
            double b = _beta[j] + t*_direction[j];
            if(b >= 0) l1norm += b; else l1norm -= b;
          }
        }
        _objVals[i] = _objVals[i]*_reg + l2pen * (b2 + t*t*d2 + 2*t*bd) +l1pen*l1norm;
        t *= _step;
      }
    }
  }
  static class GLMGradientTask extends MRTask<GLMGradientTask> {
    final GLMParameters _params;
    double _currentLambda;
    final double [] _beta;
    final protected DataInfo _dinfo;
    final double _reg;
    public double [] _gradient;
    public double    _objVal;
    protected transient boolean [] _skip;
    boolean _validate;
    double _ymu;
    GLMValidation _val;

    public GLMGradientTask(DataInfo dinfo, GLMParameters params, double lambda, double[] beta, double reg){this(dinfo,params, lambda, beta,reg,null);}
    public GLMGradientTask(DataInfo dinfo, GLMParameters params, double lambda, double[] beta, double reg, H2OCountedCompleter cc){
      super(cc);
      _dinfo = dinfo;
      _params = params;
      _beta = beta;
      _reg = reg;
      _currentLambda = lambda;
    }

    public GLMGradientTask setValidate(double ymu, boolean validate) {
      _ymu = ymu;
      _validate = validate;
      return this;
    }

//    private final void goByRowsLogistic(Chunk [] chks){
//      Row row = _dinfo.newDenseRow();
//      double [] g = _gradient;
//      double [] b = _beta;
//      for(int rid = 0; rid < chks[0]._len; ++rid) {
//        double y = row.response(0);
//        row = _dinfo.extractDenseRow(chks, rid, row);
//        if(row.bad) continue;
//        double eta = row.innerProduct(b);
//        double mu =  1.0 / (Math.exp(-eta) + 1.0);
//        double l = y == mu?0:-y * eta - Math.log(1 - mu);
//        _objVal += l;
//        double var = mu * (1 - mu);//_params.variance(mu);
//        if(var < 1e-6) var = 1e-6; // to avoid numerical problems with 0 variance
//        double d = (mu * (1 - mu));
//        d = d == 0?1e9:1/d;
//        double gval = (mu-y) / (var * d);
//        // categoricals
//        for(int i = 0; i < row.nBins; ++i)
//          g[row.binIds[i]] += gval;
//        int off = _dinfo.numStart();
//        // numbers
//        for(int j = 0; j < _dinfo._nums; ++j)
//          g[j + off] += row.numVals[j] * gval;
//        // intercept
//        if(_dinfo._intercept)
//          g[g.length-1] += gval;
//      }
//    }
    protected void goByRows(Chunk [] chks){
      Row row = _dinfo.newDenseRow();
      double [] g = _gradient;
      double [] b = _beta;
      for(int rid = 0; rid < chks[0]._len; ++rid) {
        row = _dinfo.extractDenseRow(chks, rid, row);
        if(row.bad) continue;
        double eta = row.innerProduct(b);
        double mu = _params.linkInv(eta);
        if(_validate)
          _val.add(row.response(0),eta, mu);
        _objVal += _params.likelihood(row.response(0), eta, mu);
        double var = _params.variance(mu);
        if(var < 1e-6) var = 1e-6; // to avoid numerical problems with 0 variance
        double gval = (mu-row.response(0)) / (var * _params.linkDeriv(mu));
        // categoricals
        for(int i = 0; i < row.nBins; ++i)
          g[row.binIds[i]] += gval;
        int off = _dinfo.numStart();
        // numbers
        for(int j = 0; j < _dinfo._nums; ++j)
          g[j + off] += row.numVals[j] * gval;
        // intercept
        if(_dinfo._intercept)
          g[g.length-1] += gval;
      }
    }
    @Override
    public void postGlobal(){
      _objVal = _objVal*_reg + .5 * _currentLambda * ArrayUtils.l2norm2(_beta,_dinfo._intercept);
      if(_validate) {
        _val.computeAIC();
        _val.computeAUC();
      }
      for(int j = 0; j < _beta.length - (_dinfo._intercept?1:0); ++j)
        _gradient[j] = _gradient[j]*_reg + _currentLambda * _beta[j];
      if(_dinfo._intercept)
        _gradient[_gradient.length-1] *= _reg;
    }

    // compute linear estimate by summing contributions for all columns
    // (looping by column in the outer loop to have good access pattern and to exploit sparsity)
    protected final double [] computeEtaByCols(Chunk [] chks, boolean [] skip) {
      double [] eta = MemoryManager.malloc8d(chks[0]._len);
      double [] b = _beta;
      // do categoricals first
      for(int i = 0; i < _dinfo._cats; ++i) {
        Chunk c = chks[i];
        for(int r = 0; r < c._len; ++r) { // categoricals can not be sparse
          if(skip[r] || c.isNA(r)) {
            skip[r] = true;
            continue;
          }
          int off = _dinfo.getCategoricalId(i,(int)c.at8(r));
          if(off != -1)
            eta[r] += b[off];
        }
      }
      final int numStart = _dinfo.numStart();
      // compute default eta offset for 0s
      if(_dinfo._normMul != null && _dinfo._normSub != null) {
        double off = 0;
        for (int i = 0; i < _dinfo._nums; ++i)
          off -= b[numStart + i] * _dinfo._normSub[i] * _dinfo._normMul[i];
        for(int r = 0; r < chks[0]._len; ++r)
          eta[r] += off;
      }
      // now numerics
      for (int i = 0; i < _dinfo._nums; ++i) {
        Chunk c = chks[i + _dinfo._cats];
        for (int r = c.nextNZ(-1); r < c._len; r = c.nextNZ(r)) {
          if(skip[r] || c.isNA(r)) {
            skip[r] = true;
            continue;
          }
          double d = c.atd(r);
          if (_dinfo._normMul != null)
            d *= _dinfo._normMul[i];
          eta[r] += b[numStart + i] * d;
        }
      }
      return eta;
    }

    protected void goByCols(Chunk [] chks){
      int numStart = _dinfo.numStart();
      boolean [] skp = MemoryManager.mallocZ(chks[0]._len);
      double  [] eta = computeEtaByCols(chks,skp);
      double  [] b = _beta;
      double  [] g = _gradient;
      Chunk offsetChunk = null;
      int nxs = chks.length-1; // -1 for response
      if(_dinfo._offset) {
        nxs -= 1;
        offsetChunk = chks[nxs];
      }
      Chunk responseChunk = chks[nxs];
      double eta_sum = 0;
      // compute the predicted mean and variance and gradient for each row
      for(int r = 0; r < chks[0]._len; ++r){
        if(skp[r] || responseChunk.isNA(r))
          continue;
        double off = (_dinfo._offset?offsetChunk.atd(r):0);
        double y = responseChunk.atd(r);
        double offset = off + (_dinfo._intercept?b[b.length-1]:0);
        double mu = _params.linkInv(eta[r] + offset);
        if(_validate)
          _val.add(y,eta[r] + offset, mu);
        _objVal += _params.likelihood(y,eta[r],mu);
        double var = _params.variance(mu);
        if(var < 1e-6) var = 1e-6; // to avoid numerical problems with 0 variance
        eta[r] = (mu-y) / (var * _params.linkDeriv(mu));
        eta_sum += eta[r];
      }
      // finally go over the columns again and compute gradient for each column
      // first handle eta offset and intercept
      if(_dinfo._intercept)
        g[g.length-1] = eta_sum;
      if(_dinfo._normMul != null && _dinfo._normSub != null)
        for(int i = 0; i < _dinfo._nums; ++i)
          g[numStart + i] = -_dinfo._normSub[i]*_dinfo._normMul[i]*eta_sum;
      // categoricals
      for(int i = 0; i < _dinfo._cats; ++i) {
        Chunk c = chks[i];
        for(int r = 0; r < c._len; ++r) { // categoricals can not be sparse
          if(skp[r]) continue;
          int off = _dinfo.getCategoricalId(i,(int)chks[i].at8(r));
          if(off != -1)
            g[off] += eta[r];
        }
      }
      // numerics
      for (int i = 0; i < _dinfo._nums; ++i) {
        Chunk c = chks[i + _dinfo._cats];
        for (int r = c.nextNZ(-1); r < c._len; r = c.nextNZ(r)) {
          if(skp[r] || c.isNA(r))
            continue;
          double d = c.atd(r);
          if (_dinfo._normMul != null)
            d = d*_dinfo._normMul[i];
          g[numStart + i] += eta[r] * d;
        }
      }
      _skip = skp;
    }

    private final boolean mostlySparse(Chunk [] chks){
      int cnt = 0;
      for(Chunk chk:chks)
        if(chk.isSparse())
          ++cnt;
      return cnt >= chks.length >> 1;
    }

    private boolean _forceRows;
    private boolean _forceCols;

    public GLMGradientTask forceColAccess() {
      _forceCols = true;
      _forceRows = false;
      return this;
    }
    public GLMGradientTask forceRowAccess() {
      _forceCols = false;
      _forceRows = true;
      return this;
    }
    public void map(Chunk [] chks){
      int rank = 0;
      for(int i = 0; i < _beta.length; ++i)
        if(_beta[i] != 0)
          ++rank;
      if(_validate)
        _val = new GLMValidation(_dinfo._key,_ymu,_params,rank);
      _gradient = MemoryManager.malloc8d(_beta.length);

      if(_forceCols || (!_forceRows && (chks.length >= 100 || mostlySparse(chks))))
        goByCols(chks);
      else
        goByRows(chks);
      // apply reg
    }
    public void reduce(GLMGradientTask grt) {
      _objVal += grt._objVal;
      if(_validate)
        _val.add(grt._val);
      ArrayUtils.add(_gradient, grt._gradient);
    }
  }

  /**
   * Tassk with simplified gradient computation for logistic regression (and least squares)
   * Looks like
   */
  public static class LBFGS_LogisticGradientTask extends GLMGradientTask {

    public LBFGS_LogisticGradientTask(DataInfo dinfo, GLMParameters params, double lambda, double[] beta, double reg) {
      super(dinfo, params, lambda, beta, reg);
    }

    @Override   protected void goByRows(Chunk [] chks){
      Log.info("go by rows for start row = " + chks[0].start());
      Row row = _dinfo.newDenseRow();
      double [] g = _gradient;
      double [] b = _beta;
      for(int rid = 0; rid < chks[0]._len; ++rid) {
        row = _dinfo.extractDenseRow(chks, rid, row);
        double y = -1 + 2*row.response(0);
        if(row.bad) continue;
        double eta = row.innerProduct(b);
        double d = 1 + Math.exp(-y*eta);
        _objVal += Math.log(d);
        double gval = -y*(1-1.0/d);
        // categoricals
        for(int i = 0; i < row.nBins; ++i)
          g[row.binIds[i]] += gval;
        int off = _dinfo.numStart();
        // numbers
        for(int j = 0; j < _dinfo._nums; ++j)
          g[j + off] += row.numVals[j] * gval;
        // intercept
        if(_dinfo._intercept)
          g[g.length-1] += gval;
      }
    }

    @Override protected void goByCols(Chunk [] chks){
      int numStart = _dinfo.numStart();
      boolean [] skp = MemoryManager.mallocZ(chks[0]._len);
      double  [] eta = computeEtaByCols(chks,skp);
      double  [] b = _beta;
      double  [] g = _gradient;
      Chunk offsetChunk = null;
      int nxs = chks.length-1; // -1 for response
      if(_dinfo._offset) {
        nxs -= 1;
        offsetChunk = chks[nxs];
      }
      Chunk responseChunk = chks[nxs];
      double eta_sum = 0;
      // compute the predicted mean and variance and gradient for each row
      for(int r = 0; r < chks[0]._len; ++r){
        if(skp[r] || responseChunk.isNA(r))
          continue;
        double off = (_dinfo._offset?offsetChunk.atd(r):0);

        double e = eta[r]  + off + (_dinfo._intercept?b[b.length-1]:0);

        switch(_params._family) {
          case gaussian:
            double diff = e - responseChunk.atd(r);
            _objVal += diff*diff;
            eta[r] = diff;
            break;
          case binomial:
            double y = -1 + 2*responseChunk.atd(r);
            double d = 1 + Math.exp(-y * e);
            _objVal += Math.log(d);
            eta[r] = -y * (1 - 1.0 / d);
            break;

          default:
            throw H2O.unimpl();
        }
        eta_sum += eta[r];
      }
      // finally go over the columns again and compute gradient for each column
      // first handle eta offset and intercept
      if(_dinfo._intercept)
        g[g.length-1] = eta_sum;
      if(_dinfo._normMul != null && _dinfo._normSub != null)
        for(int i = 0; i < _dinfo._nums; ++i)
          g[numStart + i] = -_dinfo._normSub[i]*_dinfo._normMul[i]*eta_sum;
      // categoricals
      for(int i = 0; i < _dinfo._cats; ++i) {
        Chunk c = chks[i];
        for(int r = 0; r < c._len; ++r) { // categoricals can not be sparse
          if(skp[r]) continue;
          int off = _dinfo.getCategoricalId(i,(int)chks[i].at8(r));
          if(off != -1)
            g[off] += eta[r];
        }
      }
      // numerics
      for (int i = 0; i < _dinfo._nums; ++i) {
        Chunk c = chks[i + _dinfo._cats];
        for (int r = c.nextNZ(-1); r < c._len; r = c.nextNZ(r)) {
          if(skp[r] || c.isNA(r))
            continue;
          double d = c.atd(r);
          if (_dinfo._normMul != null)
            d = d*_dinfo._normMul[i];
          g[numStart + i] += eta[r] * d;
        }
      }
      _skip = skp;
    }
  }
  /**
   * One iteration of glm, computes weighted gram matrix and t(x)*y vector and t(y)*y scalar.
   *
   * @author tomasnykodym
   */
  public static class GLMIterationTask extends MRTask<GLMIterationTask> {
    final Key _jobKey;
    final DataInfo _dinfo;
    final GLMParameters _glm;
    final double [] _beta;
    protected Gram  _gram;
    double [] _xy;
    double    _yy;
    GLMValidation _val; // validation of previous model
    final double _ymu;
    protected final double _reg;
    long _nobs;
    final boolean _validate;
    final float [] _thresholds;
    float [][] _newThresholds;
    int [] _ti;
    public double _objVal;

    public static final int N_THRESHOLDS = 50;
    final double _lambda;

    public  GLMIterationTask(Key jobKey, DataInfo dinfo, double lambda, GLMModel.GLMParameters glm, boolean validate, double [] beta, double ymu, double reg, float [] thresholds, H2OCountedCompleter cmp) {
      super(cmp);
      _jobKey = jobKey;
      _dinfo = dinfo;
      _glm = glm;
      _beta = beta;
      _ymu = ymu;
      _reg = reg;
      _validate = validate;
      assert glm._family != Family.binomial || thresholds != null;
      _thresholds = _validate?thresholds:null;
      _lambda = lambda;
    }

    private void sampleThresholds(int yi){
      _ti[yi] = (_newThresholds[yi].length >> 2);
      try{ Arrays.sort(_newThresholds[yi]);} catch(Throwable t){
        System.out.println("got AIOOB during sort?! ary = " + Arrays.toString(_newThresholds[yi]));
        return;
      } // sort throws AIOOB sometimes!
      for (int i = 0; i < _newThresholds.length; i += 4)
        _newThresholds[yi][i >> 2] = _newThresholds[yi][i];
    }

    @Override
    public void map(Chunk [] chks) {
      if(_jobKey != null && !Job.isRunning(_jobKey))
        throw new Job.JobCancelledException();
      // initialize
      _gram = new Gram(_dinfo.fullN(), _dinfo.largestCat(), _dinfo._nums, _dinfo._cats,true);
      // public GLMValidation(Key dataKey, double ymu, GLMParameters glm, int rank, float [] thresholds){
      if(_validate) {
        int rank = 0;
        if(_beta != null)for(double d:_beta)if(d != 0)++rank;
        _val = new GLMValidation(null, _ymu, _glm, rank, _thresholds);
      }
      _xy = MemoryManager.malloc8d(_dinfo.fullN()+1); // + 1 is for intercept

      if(_glm._family == Family.binomial && _validate){
        _ti = new int[2];
        _newThresholds = new float[2][4*N_THRESHOLDS];
      }

      // compute
      boolean sparse = FrameUtils.sparseRatio(chks) > .5;
      if(sparse) {
        for(Row r:_dinfo.extractSparseRows(chks, _beta))
          processRow(r);
        // need to adjust gradient by centered zeros
        int numStart = _dinfo.numStart();
      } else {
        Row row = _dinfo.newDenseRow();
        for(int r = 0 ; r < chks[0]._len; ++r)
          processRow(_dinfo.extractDenseRow(chks,r, row));
      }
      if(_validate && _glm._family == Family.binomial) {
        assert _val != null;
        _newThresholds[0] = Arrays.copyOf(_newThresholds[0],_ti[0]);
        _newThresholds[1] = Arrays.copyOf(_newThresholds[1],_ti[1]);
        Arrays.sort(_newThresholds[0]);
        Arrays.sort(_newThresholds[1]);
      }
    }



    protected final void processRow(Row r) {
      if(r.bad) return;
      ++_nobs;
      final double y = r.response(0);
      assert ((_glm._family != Family.gamma) || y > 0) : "illegal response column, y must be > 0  for family=Gamma.";
      assert ((_glm._family != Family.binomial) || (0 <= y && y <= 1)) : "illegal response column, y must be <0,1>  for family=Binomial. got " + y;
      final double w, eta, mu, var, z;
      final int numStart = _dinfo.numStart();
      double d = 1;
      if( _glm._family == Family.gaussian){
        w = 1;
        z = y;
        mu = 0;
        var = 1;
        eta = mu;
      } else {
        eta = r.innerProduct(_beta);
        mu = _glm.linkInv(eta);
        var = Math.max(1e-6, _glm.variance(mu)); // avoid numerical problems with 0 variance
        d = _glm.linkDeriv(mu);
        z = eta + (y-mu)*d;
        w = 1.0/(var*d*d);
      }
      if(_validate) {
        _val.add(y, eta, mu);
        if(_glm._family == Family.binomial) {
          int yi = (int) y;
          if (_ti[yi] == _newThresholds[yi].length)
            sampleThresholds(yi);
          _newThresholds[yi][_ti[yi]++] = (float) mu;
        }
      }
      _objVal += _glm.likelihood(y,eta,mu);
      assert w >= 0|| Double.isNaN(w) : "invalid weight " + w; // allow NaNs - can occur if line-search is needed!
      double wz = w * z;
      _yy += wz * z;
      for(int i = 0; i < r.nBins; ++i)
        _xy[r.binIds[i]] += wz;
      for(int i = 0; i < r.nNums; ++i){
        int id = r.numIds == null?(i + numStart):r.numIds[i];
        double val = r.numVals[i];
        _xy[id] += wz*val;
      }
      if(_dinfo._intercept)
        _xy[_xy.length-1] += wz;
      _gram.addRow(r, w);
    }


    @Override
    public void reduce(GLMIterationTask git){
      if(_jobKey == null || Job.isRunning(_jobKey)) {
        ArrayUtils.add(_xy, git._xy);
        _gram.add(git._gram);
        _yy += git._yy;
        _nobs += git._nobs;
        if (_validate) _val.add(git._val);
        if(_validate && _glm._family == Family.binomial) {
          _newThresholds[0] = ArrayUtils.join(_newThresholds[0], git._newThresholds[0]);
          _newThresholds[1] = ArrayUtils.join(_newThresholds[1], git._newThresholds[1]);
          if (_newThresholds[0].length >= 2 * N_THRESHOLDS) {
            for (int i = 0; i < 2 * N_THRESHOLDS; i += 2)
              _newThresholds[0][i >> 1] = _newThresholds[0][i];
          }
          if (_newThresholds[0].length > N_THRESHOLDS)
            _newThresholds[0] = Arrays.copyOf(_newThresholds[0], N_THRESHOLDS);
          if (_newThresholds[1].length >= 2 * N_THRESHOLDS) {
            for (int i = 0; i < 2 * N_THRESHOLDS; i += 2)
              _newThresholds[1][i >> 1] = _newThresholds[1][i];
          }
          if (_newThresholds[1].length > N_THRESHOLDS)
            _newThresholds[1] = Arrays.copyOf(_newThresholds[1], N_THRESHOLDS);
        }
        _objVal += git._objVal;
        super.reduce(git);
      }
    }

    @Override protected void postGlobal(){
      _objVal *= _reg;
      _gram.mul(_reg);
      for(int i = 0; i < _xy.length; ++i)
        _xy[i] *= _reg;
      if(_val != null){
        _val.computeAIC();
        _val.computeAUC();
      }
    }

    public boolean hasNaNsOrInf() {
      return ArrayUtils.hasNaNsOrInfs(_xy) || _gram.hasNaNsOrInfs();
    }
  }

//  public static class GLMValidationTask<T extends GLMValidationTask<T>> extends MRTask<T> {
//    protected final GLMModel _model;
//    protected GLMValidation _res;
//    public final double _lambda;
//    public boolean _improved;
//    Key _jobKey;
//    public static Key makeKey(){return Key.make("__GLMValidation_" + Key.make().toString());}
//    public GLMValidationTask(GLMModel model, double lambda){this(model,lambda,null);}
//    public GLMValidationTask(GLMModel model, double lambda, H2OCountedCompleter completer){super(completer); _lambda = lambda; _model = model;}
//    @Override public void map(Chunk[] chunks){
//      _res = new GLMValidation(null,_model._ymu,_model._parms,_model.rank(_lambda));
//      final int nrows = chunks[0]._len;
//      double [] row   = MemoryManager.malloc8d(_model._output._names.length);
//      float  [] preds = MemoryManager.malloc4f(_model._parms._family == Family.binomial?3:1);
//      OUTER:
//      for(int i = 0; i < nrows; ++i){
//        if(chunks[chunks.length-1].isNA(i))continue;
//        for(int j = 0; j < chunks.length-1; ++j){
//          if(chunks[j].isNA(i))continue OUTER;
//          row[j] = chunks[j].atd(i);
//        }
//        _model.score0(row, preds);
//        double response = chunks[chunks.length-1].atd(i);
//        _res.add(response, _model._parms._family == Family.binomial?preds[2]:preds[0]);
//      }
//    }
//    @Override public void reduce(GLMValidationTask gval){_res.add(gval._res);}
//    @Override public void postGlobal(){
//      _res.computeAIC();
//      _res.computeAUC();
//    }
//  }
  // use general score to reduce number of possible different code paths
//  public static class GLMXValidationTask extends GLMValidationTask<GLMXValidationTask>{
//    protected final GLMModel [] _xmodels;
//    protected GLMValidation [] _xvals;
//    long _nobs;
//    final float [] _thresholds;
//    public static Key makeKey(){return Key.make("__GLMValidation_" + Key.make().toString());}
//
//    public GLMXValidationTask(GLMModel mainModel,double lambda, GLMModel [] xmodels, float [] thresholds){this(mainModel,lambda,xmodels,thresholds,null);}
//    public GLMXValidationTask(GLMModel mainModel,double lambda, GLMModel [] xmodels, float [] thresholds, final H2OCountedCompleter completer){
//      super(mainModel, lambda,completer);
//      _xmodels = xmodels;
//      _thresholds = thresholds;
//    }
//    @Override public void map(Chunk [] chunks) {
//      long gid = chunks[0].start();
//      _xvals = new GLMValidation[_xmodels.length];
//      for(int i = 0; i < _xmodels.length; ++i)
//        _xvals[i] = new GLMValidation(null,_xmodels[i]._ymu,_xmodels[i]._parms,_xmodels[i]._output.rank(),_thresholds);
//      final int nrows = chunks[0]._len;
//      double [] row   = MemoryManager.malloc8d(_xmodels[0]._output._names.length);
//      float  [] preds = MemoryManager.malloc4f(_xmodels[0]._parms._family == Family.binomial?3:1);
//      OUTER:
//      for(int i = 0; i < nrows; ++i){
//        if(chunks[chunks.length-1].isNA(i))continue;
//        for(int j = 0; j < chunks.length-1; ++j) {
//          if(chunks[j].isNA(i))continue OUTER;
//          row[j] = chunks[j].atd(i);
//        }
//        ++_nobs;
//        final int mid = (int)((i + gid)  % _xmodels.length);
//        final GLMModel model = _xmodels[mid];
//        final GLMValidation val = _xvals[mid];
//        model.score0(row, preds);
//        double response = chunks[chunks.length-1].at8(i);
//        val.add(response, model._parms._family == Family.binomial?preds[2]:preds[0]);
//      }
//    }
//    @Override public void reduce(GLMXValidationTask gval){
//      _nobs += gval._nobs;
//      for(int i = 0; i < _xvals.length; ++i)
//        _xvals[i].add(gval._xvals[i]);}
//
//    @Override public void postGlobal() {
//      H2OCountedCompleter cmp = (H2OCountedCompleter)getCompleter();
//      if(cmp != null)cmp.addToPendingCount(_xvals.length + 1);
//      for (int i = 0; i < _xvals.length; ++i) {
//        _xvals[i].computeAIC();
//        _xvals[i].computeAUC();
//        _xvals[i].nobs = _nobs - _xvals[i].nobs;
//        GLMModel.setXvalidation(cmp, _xmodels[i]._key, _lambda, _xvals[i]);
//      }
//      GLMModel.setXvalidation(cmp, _model._key, _lambda, new GLMXValidation(_model, _xmodels, _xvals, _lambda, _nobs,_thresholds));
//    }
//  }
}
