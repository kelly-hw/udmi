#!/usr/bin/env python3
""" Unit testing for sequence report generator """
import unittest
from importlib import util, machinery
from dataclasses import dataclass

spec = util.spec_from_loader(
    "sequence_report",
    machinery.SourceFileLoader("sequence_report", "bin/sequencer_report"),
)
seq = util.module_from_spec(spec)
spec.loader.exec_module(seq)


SAMPLE_SEQUENCE = """
## extra_config (BETA)

Check that the device correctly handles an extra out-of-schema field

1. Step 1:
    * Substep 1
    * Substep 2
1. Step 2
1. Step `3`
"""

SAMPLE_PARTIAL_SEQUENCE = """
## extra_config (BETA)

Check that the device correctly handles an extra out-of-schema field

1. Step 1:
    * Substep 1
    * Substep 2
1. Step 2
"""

SAMPLE_EXPECTED = """
✓   1. Step 1
✓   2. Multistep 2
      * line a
      * line b

----------------
✕   3. Step 3 ✕
----------------

    4. Step 4
"""

REAL_SEQUENCE = """
## empty_enumeration (PREVIEW)

check enumeration of nothing at all

1. Update config before enumeration not active:
    * Add `discovery` = { "enumerate": {  } }
1. Wait for enumeration not active
1. Update config before matching enumeration generation:
    * Add `discovery.generation` = `generation start time`
1. Wait for matching enumeration generation
1. Update config before cleared enumeration generation:
    * Remove `discovery.generation`
1. Wait for cleared enumeration generation
1. Check that no family enumeration
1. Check that no feature enumeration
1. Check that no point enumeration
"""

REAL_SEQUENCE_PARTIAL = """
## empty_enumeration (PREVIEW)

check enumeration of nothing at all

1. Update config before enumeration not active:
    * Add `discovery` = { "enumerate": {  } }
1. Wait for enumeration not active
1. Update config before matching enumeration generation:
    * Add `discovery.generation` = `generation start time`
1. Wait for matching enumeration generation
"""


@dataclass
class SampleTestResult:
  result: str = "fail"
  name: str = "extra_config"


class TestSequencerReport(unittest.TestCase):
  """ Unit tests for sequence report generation """

  def test_sequence_get_steps(self):
    steps = seq.Sequence.get_steps(SAMPLE_SEQUENCE)
    # print(json.dumps(steps))
    self.assertEqual(steps[0][1], "    * Substep 1")
    self.assertEqual(steps[2][0], "1. Step `3`")

  def test_indent_with_prefix(self):
    formatted = seq.Sequence.indent("some string", 5, ">")
    self.assertEqual(formatted, ">    some string")

  def test_longest_line(self):
    lines = ["a", "abc", "abcdef"]
    self.assertEqual(seq.Sequence.longest_line_length(lines), 6)

  def test_failing_step(self):
    # method doesn't actually care what the contents of the list
    # so just use a fixed list
    ref = ["Step 1", "Step 2", "Step 3", " Step 4"]
    act = ["Step 1", "Step 2"]
    failing_step = seq.Sequence.failing_step(ref, act)
    self.assertEqual(failing_step, 1)

    passed_all = seq.Sequence.failing_step(ref, ref)
    # if non failed is the index of the last step
    self.assertEqual(passed_all, 3)

  def test_classed_steps(self):
    ref = ["Step 1", "Step 2", "Step 3", " Step 4"]
    act = ["Step 1", "Step 2"]

    # pylint: disable-next=unused-variable
    done, fail, todo = seq.Sequence.classify_steps(ref, act)
    self.assertEqual(len(done), 1)
    self.assertEqual(len(todo), 2)

    # pylint: disable-next=unused-variable
    done, fail, todo = seq.Sequence.classify_steps(ref, ref, "pass")
    self.assertEqual(len(done), 4)
    self.assertEqual(len(todo), 0)

    # pylint: disable-next=unused-variable
    done, fail, todo = seq.Sequence.classify_steps(ref, ref, "fail")
    self.assertEqual(len(done), 3)

  def test_formatter(self):
    ref = [
        ["1. Step 1"],
        ["1. Multistep 2", "  * line a", "  * line b"],
        ["1. Step 3"],
        ["1. Step 4"],
    ]
    act = [["1. Step 1"], ["1. Multistep 2", "  * line a", "  * line b"]]

    formatted = seq.Sequence.format(ref, act, SampleTestResult())
    print(formatted)
    print(SAMPLE_EXPECTED)
    self.assertEqual(formatted, SAMPLE_EXPECTED)

  def test_single_step(self):
    steps = seq.Sequence.get_steps(SAMPLE_SEQUENCE)
    # print(json.dumps(steps))
    self.assertEqual(steps[0][1], "    * Substep 1")
    self.assertEqual(steps[2][0], "1. Step `3`")


if __name__ == "__main__":
  unittest.main()
