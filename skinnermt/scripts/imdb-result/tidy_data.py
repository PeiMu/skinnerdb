import math
import numpy as np
import pandas as pd


# Define a function to extract Millis number from each line in a text file
def extract_millis(filename):
    query_millis = {}
    with open(filename, 'r') as file:
        next(file)  # Skip the header line
        for line in file:
            data = line.strip().split(',')
            query = data[0]
            is_warmup = data[1]
            millis = int(data[2])
            if is_warmup.lower() == 'true':
                continue  # Skip rows where IsWarmup is true
            if query in query_millis:
                query_millis[query] += millis
            else:
                query_millis[query] = millis
    return query_millis


# Define a function to calculate geometric mean and standard deviation for each query
def calculate_stats(file_list):
    all_query_millis = {}
    for filename in file_list:
        query_millis = extract_millis(filename)
        for query, millis_list in query_millis.items():
            if query in all_query_millis:
                all_query_millis[query].extend([millis_list])
            else:
                all_query_millis[query] = [millis_list]

    all_query_stats = []
    for query, millis_list in all_query_millis.items():
        # geomean = np.prod(millis_list) ** (1 / len(millis_list))
        geomean = np.exp(np.log(millis_list).mean())
        std_dev = np.std(millis_list)
        print(f"Query: {query}, Geometric Mean: {geomean}, Standard Deviation: {std_dev}")
        all_query_stats.append({'Query': query, 'Geometric Mean': geomean, 'Standard Deviation': std_dev})
    df = pd.DataFrame(all_query_stats)
    # Write the result into an Excel file
    df.to_excel('query_statistics-4.xlsx', index=False)


# List of filenames for your 10 text files
file_list = ['imdb-hpa-4_1.txt', 'imdb-hpa-4_2.txt', 'imdb-hpa-4_3.txt', 'imdb-hpa-4_4.txt', 'imdb-hpa-4_5.txt', 'imdb-hpa-4_6.txt', 'imdb-hpa-4_7.txt', 'imdb-hpa-4_8.txt', 'imdb-hpa-4_9.txt', 'imdb-hpa-4_10.txt']

# Call the function to calculate statistics
calculate_stats(file_list)

