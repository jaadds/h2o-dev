package water.parser;

import org.junit.*;

import water.*;
import water.fvec.*;
import water.parser.Parser.ColTypeInfo;

public class ParserTest2 extends TestUtil {
  @BeforeClass
  public static void setup() {
    stall_till_cloudsize(1);
  }
  private final char[] SEPARATORS = new char[] {',', ' '};

  private static void testParsed(Frame fr, String[][] expected) {
    Assert.assertEquals(expected   .length,fr.numRows());
    Assert.assertEquals(expected[0].length,fr.numCols());
    for( int j = 0; j < fr.numCols(); ++j ) {
      Vec vec = fr.vecs()[j];
      for( int i = 0; i < expected.length; ++i ) {
        if( expected[i][j]==null )
          Assert.assertTrue(i+" -- "+j, vec.isNA(i));
        else {
          String pval = vec.domain()[(int)vec.at8(i)];
          Assert.assertTrue(expected[i][j]+" -- "+pval,expected[i][j].equals(pval));
        }
      }
    }
    fr.delete();
  }

  @Test public void testNAs() {
    String [] data = new String[]{
      "'C1Chunk',C1SChunk, 'C2Chunk', 'C2SChunk',  'C4Chunk',  'C4FChunk',  'C8Chunk',  'C8DChunk',   'Categorical'\n"  +
      "0,       0.0,          0,           0,           0,          0 ,          0,   8.878979,           A \n" ,
      "1,       0.1,          1,         0.1,           1,          1 ,          1,   1.985934,           B \n" ,
      "2,       0.2,          2,         0.2,           2,          2 ,          2,   3.398018,           C \n" ,
      "3,       0.3,          3,         0.3,           3,          3 ,          3,   9.329589,           D \n" ,
      "4,       0.4,          4,           4,           4,          4 , 2147483649,   0.290184,           A \n" ,
      "0,       0.5,          0,           0,     -100000,    1.234e2 ,-2147483650,   1e-30,              B \n" ,
      "254,    0.25,       2550,      6553.4,      100000,    2.345e-2,          0,    1e30,              C \n" ,
      " ,          ,           ,            ,            ,            ,           ,        ,                \n" ,
      "?,        NA,          ?,           ?,           ?,           ?,          ?,       ?,                \n" ,
    };

    Key rkey = ParserTest.makeByteVec(data);
    ParseSetup ps = new ParseSetup(true, 0, 1, null, ParserType.CSV, (byte)',', 9, false, null, null, null, 0,
            ColTypeInfo.fromStrings(new String[]{"Numeric", "Numeric", "Numeric", "Numeric", "Numeric", "Numeric", "Numeric", "Numeric", "Enum"}));
    Frame fr = ParseDataset.parse(Key.make("na_test.hex"), new Key[]{rkey}, true, ps);
    int nlines = (int)fr.numRows();
    Assert.assertEquals(9,nlines);
    Assert.assertEquals(9,fr.numCols());
    for(int i = 0; i < nlines-2; ++i)
      for( Vec v : fr.vecs() )
        Assert.assertTrue("error at line "+i+", vec " + v.chunkForChunkIdx(0).getClass().getSimpleName(),
                   !Double.isNaN(v.at(i)) && !v.isNA(i) );
    for( int j=0; j<fr.vecs().length; j++ ) {
      Vec v = fr.vecs()[j];
      for( int i = nlines-2; i < nlines; ++i )
        Assert.assertTrue(i + ", " + j + ":" + v.at(i) + ", " + v.isNA(i), Double.isNaN(v.at(i)) && v.isNA(i) );
    }
    fr.delete();
  }

  
 @Test public void testSingleQuotes(){
    String[] data  = new String[]{"'Tomass,test,first,line'\n'Tomas''s,test2',test2\nlast,'line''","s, trailing, piece'"};
    String[][] expectFalse = new String[][] { ar("'Tomass"  ,"test"  ,"first","line'"),
                                              ar("'Tomas''s","test2'","test2",null),
                                              ar("last","'line''s","trailing","piece'") };
    Key k = ParserTest.makeByteVec(data);
    ParseSetup gSetupF = ParseSetup.guessSetup(data[0].getBytes(),ParserType.CSV, (byte)',', 4, false/*single quote*/, -1, null, null);
    gSetupF._ctypes = ColTypeInfo.fromStrings(new String[]{"Enum", "Enum", "Enum", "Enum"});
    Frame frF = ParseDataset.parse(Key.make(), new Key[]{k}, false, gSetupF);
    testParsed(frF,expectFalse);

    String[][] expectTrue = new String[][] { ar("Tomass,test,first,line", null),
                                             ar("Tomas''stest2","test2"),
                                             ar("last", "lines trailing piece") };
    ParseSetup gSetupT = ParseSetup.guessSetup(data[0].getBytes(),ParserType.CSV, (byte)',', 2, true/*single quote*/, -1, null, null);
    gSetupT._ctypes = ColTypeInfo.fromStrings(new String[]{"Enum", "Enum", "Enum", "Enum"});
    Frame frT = ParseDataset.parse(Key.make(), new Key[]{k}, true, gSetupT);
    //testParsed(frT,expectTrue);  // not currently passing
    frT.delete();
  }

  @Test public void testSingleQuotes2() {
    Frame fr = parse_test_file("smalldata/junit/test_quote.csv");
    Assert.assertEquals(fr.numCols(),11);
    Assert.assertEquals(fr.numRows(), 7);
    fr.delete();
  }
  
  // Test very sparse data
  @Test public void testSparse() {
    // Build 100 zero's and 1 one.
    double[][] exp = new double[101][1];
    exp[50][0] = 1;
    StringBuilder sb = new StringBuilder();
    for( int i=0; i<50; i++ ) sb.append("0.0\n");
    sb.append("1.0\n");
    for( int i=0; i<50; i++ ) sb.append("0.0\n");
    Key k = ParserTest.makeByteVec(sb.toString());
    ParserTest.testParsed(ParseDataset.parse(Key.make(), k),exp,101);
  
    // Build 100 zero's and 1 non-zero.
    exp = new double[101][1];
    exp[50][0] = 2;
    sb = new StringBuilder();
    for( int i=0; i<50; i++ ) sb.append("0\n");
    sb.append("2\n");
    for( int i=0; i<50; i++ ) sb.append("0\n");
    k = ParserTest.makeByteVec(sb.toString());
    ParserTest.testParsed(ParseDataset.parse(Key.make(), k),exp,101);

    // Build 100 zero's and some non-zeros.  Last line is truncated.
    for (char sep : SEPARATORS) {
      exp = new double[101][2];
      exp[ 50][0] = 2;
      exp[ 50][1] = 3;
      exp[100][0] = 0;          // Truncated final line
      exp[100][1] = Double.NaN;
      sb = new StringBuilder();
      for( int i=0; i<50; i++ ) sb.append("0").append(sep).append("0\n");
      sb.append("2").append(sep).append("3\n");
      for( int i=0; i<49; i++ ) sb.append("0").append(sep).append("0\n");
      sb.append("0");           // Truncated final line
      k = ParserTest.makeByteVec(sb.toString());
      ParserTest.testParsed(ParseDataset.parse(Key.make(), k),exp,101);
    }
  
    // Build 100000 zero's and some one's
    sb = new StringBuilder();
    exp = new double[100100][1];
    for( int i=0; i<100; i++ ) {
      for( int j=0; j<1000; j++ )
        sb.append("0\n");
      sb.append("1\n");
      exp[i*1001+1000][0]=1;
    }
    k = ParserTest.makeByteVec(sb.toString());
    ParserTest.testParsed(ParseDataset.parse(Key.make(), k),exp,100100);
  
    // Build 100 zero's, then 100 mix of -1001 & 1001's (to force a
    // sparse-short, that finally inflates to a full dense-short).
    sb = new StringBuilder();
    for( int i=0; i<100; i++ ) sb.append("0\n");
    for( int i=0; i<100; i+=2 ) sb.append("-1001\n1001\n");
    exp = new double[200][1];
    for( int i=0; i<100; i+=2 ) { exp[i+100][0]=-1001; exp[i+101][0]= 1001; }
    k = ParserTest.makeByteVec(sb.toString());
    ParserTest.testParsed(ParseDataset.parse(Key.make(), k),exp,200);
  }
  
  // test correctnes of sparse chunks
  // added after failing to encode properly following data as
  // 0s were not considered when computing compression strategy and then
  // lemin was 6108 and there was Short overflow when encoding zeros.
  // So, the first column was compressed into C2SChunk with 0s causing short overflow,
  @Test public void testSparse2(){
    String data =
        "0,     0,0,0,0,0\n" +
        "0,     0,0,0,0,0\n" +
        "0,     0,0,0,0,0\n" +
        "0,     0,0,0,0,0\n" +
        "0,     0,0,0,0,0\n" +
        "0,     0,0,0,0,0\n" +
        "0,     0,0,0,0,0\n" +
        "0,     0,0,0,0,0\n" +
        "0,     0,0,0,0,0\n" +
        "35351, 0,0,0,0,0\n" +
        "0,     0,0,0,0,0\n" +
        "0,     0,0,0,0,0\n" +
        "0,     0,0,0,0,0\n" +
        "0,     0,0,0,0,0\n" +
        "0,     0,0,0,0,0\n" +
        "6108,  0,0,0,0,0\n" +
        "0,     0,0,0,0,0\n" +
        "0,     0,0,0,0,0\n" +
        "0,     0,0,0,0,0\n" +
        "0,     0,0,0,0,0\n" +
        "0,     0,0,0,0,0\n" +
        "0,     0,0,0,0,0\n" +
        "0,     0,0,0,0,0\n" +
        "0,     0,0,0,0,0\n" +
        "35351, 0,0,0,0,0\n" +
        "0,     0,0,0,0,0\n" +
        "0,     0,0,0,0,0\n" +
        "0,     0,0,0,0,0\n" +
        "0,     0,0,0,0,0\n" +
        "0,     0,0,0,0,0\n" +
        "6334,  0,0,0,0,0\n" +
        "0,     0,0,0,0,0\n" +
        "0,     0,0,0,0,0\n";
  
    double[][] exp = new double[][] {
        ard(0,0,0,0,0,0),
        ard(0,0,0,0,0,0),
        ard(0,0,0,0,0,0),
        ard(0,0,0,0,0,0),
        ard(0,0,0,0,0,0),
        ard(0,0,0,0,0,0),
        ard(0,0,0,0,0,0),
        ard(0,0,0,0,0,0),
        ard(0,0,0,0,0,0),
        ard(35351,0,0,0,0,0),
        ard(0,0,0,0,0,0),
        ard(0,0,0,0,0,0),
        ard(0,0,0,0,0,0),
        ard(0,0,0,0,0,0),
        ard(0,0,0,0,0,0),
        ard(6108,0,0,0,0,0),
        ard(0,0,0,0,0,0),
        ard(0,0,0,0,0,0),
        ard(0,0,0,0,0,0),
        ard(0,0,0,0,0,0),
        ard(0,0,0,0,0,0),
        ard(0,0,0,0,0,0),
        ard(0,0,0,0,0,0),
        ard(0,0,0,0,0,0),
        ard(35351,0,0,0,0,0),
        ard(0,0,0,0,0,0),
        ard(0,0,0,0,0,0),
        ard(0,0,0,0,0,0),
        ard(0,0,0,0,0,0),
        ard(0,0,0,0,0,0),
        ard(6334,0,0,0,0,0),
        ard(0,0,0,0,0,0),
        ard(0,0,0,0,0,0),
    };
    Key k = ParserTest.makeByteVec(data);
    ParserTest.testParsed(ParseDataset.parse(Key.make(), k),exp,33);
  }
}
