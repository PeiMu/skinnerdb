import re
import os
import shutil


def match_content(pattern, text):
    # Extracting the movie_with_costumes using regex
    match = re.search(pattern, text)
    if match:
        return match.endpos
    else:
        return None


def delete_content(file_path, pattern):
    with open(file_path, 'r+') as file:
        # Read the entire file
        lines = file.readlines()
        # Move to the beginning of the file
        file.seek(0)
        # Iterate through lines and write those which don't match the content pattern
        for line in lines:
            # if not re.match(pattern, line.strip()):
            if pattern != line:
                file.write(line)
        # Truncate the remaining content (in case the new content is smaller than the original content)
        file.truncate()


# Pattern to match
pattern_to_match = r'------------'

# File path of the file containing many similar patterns
file_with_patterns = "imdb/skinnerResults.txt"

# File path of the file to be edited
file_to_edit = "imdb/pgCheck.txt"


shutil.copy("imdb/pgResults.txt", file_to_edit)

# Read patterns from the file and delete corresponding content
with open(file_with_patterns, 'r') as pattern_file:
    content = False
    patterns = pattern_file.readlines()
    for idx in range(0, len(patterns)):
        end_pos = match_content(pattern_to_match, patterns[idx])
        if end_pos:
            if not content:
                content = True
            elif idx != len(patterns)-1:
                x = patterns[idx+1]
                delete_content(file_to_edit, patterns[idx+1])
                print(f"Content deleted from file based on pattern: {patterns[idx+1]}")

print("All content deleted based on patterns from file:", file_with_patterns)
