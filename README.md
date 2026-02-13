# SDP-detection-LLMs
Dataset used in Paper (includes anonymized files)

Please ignore past versions of the repository!

Contained within are the 100 classes, enums, and interfaces analyzed.

To use the Java Anonymizer:

(0) Copy JavaAnonymizerStandalone into your project, change the package declaration

(1) When creating the object, provide it with a list of Strings that you don't want anonymized

(2) Split code into a format where each line is a string and run it through anonymizer

Output will be in the same format as the input.

To use the parser: 

Place files in a directory "analysis" before running.  If bugs occur, please email benjynstrauss@gmail.com with the stack trace.

# To File Reviews:

(1) Choose a file to review

(2) Select which Pattern(s) the file fits from list-of-patterns.txt

(2a) You can also mention the confidence in your assesment and the correctness of the pattern

(3) Email your selections to benjynstrauss@gmail.com **(do NOT upload to the repo!)**

(4) Repeat as desired


(Don't worry about duplicate reviews with other reviewers, more is always better)

Files like "class#0.txt" are anonymized versions of other files – they do not need reviews.

The Java Anonymizer and LLMOutput Parser are there for reviewer use – they do not need reviews.
