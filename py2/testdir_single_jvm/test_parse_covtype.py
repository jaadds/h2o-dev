import unittest, sys
sys.path.extend(['.','..','../..','py'])
import os

import h2o2 as h2o
import h2o_cmd, h2o_import as h2i, h2o_browse as h2b
from h2o_test import find_file, dump_json, verboseprint

expectedZeros = [0, 4914, 656, 24603, 38665, 124, 13, 5, 1338, 51, 320216, 551128, 327648, 544044, 577981, 
573487, 576189, 568616, 579415, 574437, 580907, 580833, 579865, 548378, 568602, 551041, 
563581, 580413, 581009, 578167, 577590, 579113, 576991, 571753, 580174, 547639, 523260, 
559734, 580538, 578423, 579926, 580066, 465765, 550842, 555346, 528493, 535858, 579401, 
579121, 580893, 580714, 565439, 567206, 572262, 0]

CAUSE_FAIL = False

def assertEqualMsg(a, b): assert a == b, "%s %s" % (a, b)

def parseKeyIndexedCheck(frames_result, multiplyExpected):
    # get the name of the frame?
    print ""
    frame = frames_result['frames'][0]
    byteSize = frame['byteSize']
    rows = frame['rows']
    columns = frame['columns']
    for i,c in enumerate(columns):
        label = c['label']
        stype = c['type']
        missing = c['missing']
        zeros = c['zeros']
        domain = c['domain']
        print "column: %s label: %s type: %s missing: %s zeros: %s domain: %s" %\
            (i,label,stype,missing,zeros,domain)

        # files are concats of covtype. so multiply expected
        assertEqualMsg(zeros, expectedZeros[i] * multiplyExpected)
        assertEqualMsg(label,"C%s" % (i+1))
        assertEqualMsg(stype,"int")
        assertEqualMsg(missing, 0)
        assertEqualMsg(domain, None)

class Basic(unittest.TestCase):
    def tearDown(self):
        h2o.check_sandbox_for_errors()

    @classmethod
    def setUpClass(cls):
        h2o.init()

    @classmethod
    def tearDownClass(cls):
        h2o.tear_down_cloud()

    def test_parse_covtype(self):

        tryList = [
            ('covtype.data', 1, 30),
            ('covtype20x.data', 20, 120),
        ]

        for (csvFilename, multiplyExpected, timeoutSecs) in tryList:
            # h2o-dev doesn't take ../.. type paths? make find_file return absolute pathj
            a_node = h2o.nodes[0]

            importFolderPath = os.path.expanduser("~/home-0xdiag-datasets/standard")
            csvPathname = importFolderPath + "/" + csvFilename
            importResult = a_node.import_files(path=csvPathname)

            # print "importResult:", dump_json(importResult)
            hex_key = importResult['keys'][0]

            if CAUSE_FAIL:
                frames_result = a_node.frames(key=k, len=5, timeoutSecs=timeoutSecs)
            # print "frames_result from the first importResult key", dump_json(frames_result)

            parseResult = a_node.parse(key=hex_key, timeoutSecs=timeoutSecs, chunkSize=4194304*4)
            pA = h2o_cmd.ParseObj(parseResult)
            iA = h2o_cmd.InspectObj(pA.parse_key, expectedNumRows=581012*multiplyExpected, 
                expectedNumCols=55, expectedMissinglist=[])
            print iA.missingList, iA.labelList, iA.numRows, iA.numCols

            for i in range(0):
                print "Summary on column", i
                co = h2o_cmd.runSummary(key=hex_key, column=i)
                coList = [co.base, len(co.bins), len(co.data), co.domain, co.label, co.maxs, co.mean, co.mins, co.missing,
                    co.ninfs, co.pctiles, co.pinfs, co.precision, co.sigma, co.str_data, co.stride, co.type, co.zeros]

                for k,v in co:
                    print k, v


            # illegal
            # parseResult = a_node.parse(key=k, timeoutSecs=timeoutSecs, chunkSize=3000000)
            # fail
            # parseResult = a_node.parse(key=k, timeoutSecs=timeoutSecs, chunkSize=4194304/2)
            # fail
            # parseResult = a_node.parse(key=k, timeoutSecs=timeoutSecs)
            k = parseResult['frames'][0]['key']['name']
            # print "parseResult:", dump_json(parseResult)
            frames_result = a_node.frames(key=k, len=5)
            # print "frames_result from the first parseResult key", dump_json(frames_result)
            
            parseKeyIndexedCheck(frames_result, multiplyExpected)

if __name__ == '__main__':
    h2o.unit_main()
