# SDP-detection-LLMs
Dataset used in Paper (includes anonymized files)

Please ignore past versions of the repository.  They do not have useful information.

Contained within are the 100 classes, enums, and interfaces analyzed.

To use the Java Anonymizer:

(0) Copy JavaAnonymizerStandalone into your project, change the package declaration

(1) When creating the object, provide it with a list of Strings that you don't want anonymized

(2) Split code into a format where each line is a string and run it through anonymizer

Output will be in the same format as the input.

**To use the parser:** 

Same rough instructions as above. Place files in a directory "analysis" before running.  If bugs occur, please email benjynstrauss@gmail.com with the stack trace.

# To File Reviewers:

(1) Choose a .java file to review from one of the subfolders (not the anoymizer or the parser)

(2) Select which Pattern(s) the file fits from list-of-patterns.txt (alternatively, you could add a new pattern)

(2a) If possible, please add your confidence (%) in your assesment of the pattern and the % correctness of the pattern

(3) Repeat as desired

(4) Email your selections to benjynstrauss@gmail.com **(do NOT upload to the repo!)**

Feel free to reach me at benjynstrauss@gmail.com if you have any questions – thanks!

(Don't worry about duplicate reviews with other reviewers, more reviews are always better)

Files like "class#0.txt" are anonymized versions of other files – they do not need reviews.
