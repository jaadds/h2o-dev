import sys, os
sys.path.insert(1, "../../../")
import h2o

import numpy as np
from sklearn import ensemble
this_file_dir = os.path.dirname(os.path.realpath(__file__))
h2o_home_dir = this_file_dir + "/../../../../"

def smallcatGBM(ip,port):
  # Training set has 26 categories from A to Z
  # Categories A, C, E, G, ... are perfect predictors of y = 1
  # Categories B, D, F, H, ... are perfect predictors of y = 0

  # Connect to h2o
  h2o.init(ip,port)

  #Log.info("Importing alphabet_cattest.csv data...\n")
  alphabet = h2o.import_frame(path=h2o.locate("smalldata/gbm_test/alphabet_cattest.csv"))
  alphabet["y"] = alphabet["y"].asfactor()
  #Log.info("Summary of alphabet_cattest.csv from H2O:\n")
  #alphabet.summary()

  # Prepare data for scikit use
  trainData = np.loadtxt(h2o.locate("smalldata/gbm_test/alphabet_cattest.csv"), delimiter=',', skiprows=1, converters={0:lambda s: ord(s.split("\"")[1])})
  trainDataResponse = trainData[:,1]
  trainDataFeatures = trainData[:,0]
  
  # Train H2O GBM Model:
  #Log.info("H2O GBM (Naive Split) with parameters:\nntrees = 1, max_depth = 1, nbins = 100\n")
  gbm_h2o = h2o.gbm(x=alphabet[['X']], y=alphabet["y"], ntrees=1, max_depth=1, nbins=100)
  gbm_h2o.show()
  
  # Train scikit GBM Model:
  # Log.info("scikit GBM with same parameters:")
  gbm_sci = ensemble.GradientBoostingClassifier(n_estimators=1, max_depth=1, max_features=None)
  gbm_sci.fit(trainDataFeatures[:,np.newaxis],trainDataResponse)

if __name__ == "__main__":
  h2o.run_test(sys.argv, smallcatGBM)
