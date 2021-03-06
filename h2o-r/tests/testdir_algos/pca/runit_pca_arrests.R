setwd(normalizePath(dirname(R.utils::commandArgs(asValues=TRUE)$"f")))
source('../../h2o-runit.R')

# Test PCA on arrests.csv
test.pca.arrests <- function(conn) {
  Log.info("Importing arrests.csv data...\n")
  arrests.hex <- h2o.uploadFile(conn, locate("smalldata/pca_test/USArrests.csv"))
  arrests.sum <- summary(arrests.hex)
  print(arrests.sum)
  arrests.data <- read.csv(locate("smalldata/pca_test/USArrests.csv"), header = TRUE)
  
  for(i in 1:4) {
    Log.info(paste("H2O PCA with ", i, " dimensions:\n", sep = ""))
    Log.info(paste("Using these columns: ", colnames(arrests.hex)))
    arrests.pca.h2o <- h2o.prcomp(training_frame = arrests.hex, k = as.numeric(i))
    print(arrests.pca.h2o)
  }
  
  testEnd()
}

doTest("PCA Test: USArrests Data", test.pca.arrests)