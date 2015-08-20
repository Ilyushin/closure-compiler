import sysconfig, csv, os

dir = os.path.dirname(__file__)
path_results = path_jerry = os.path.join(dir, 'results')

with open(path_results+'/working_time_passes.csv', 'rb') as csvfile:
     csv_reader = csv.reader(csvfile, delimiter=',')
     
     #row of the dict consist of following fields:
     # - pass name
     # - number of times without empty result
     # - average reducing code amount 
     # - average execution time (milliseconds)
     result_dict = {}
     for row in csv_reader:
         if row[0] == 'FileName': continue
         
         diff = int(row[3])-int(row[4]) 
         if diff > 0:
             result_dict.setdefault(row[1],[0,0,0])
             cur_row = result_dict[row[1]] 
             cur_row[0] += 1
             cur_row[1] += diff
             cur_row[2] += int(row[2])
        
     if result_dict:
         csvfile = open(path_results+'/analyze_result.csv', 'w+')
         csv_writer = csv.writer(csvfile, delimiter=' ', quoting=csv.QUOTE_NONE, escapechar=' ', quotechar='')
         csv_writer.writerow(['Pass name',',Number of times',',Average reducing code amount',',Average execution time (milliseconds)'])
         
         for key, value in result_dict.iteritems():
             value[1] = value[1]/value[0]
             value[2] = value[2]/value[0]
             
             newStr = []
             newStr.append(key.replace('pass:','').strip())
             newStr.append(',' + str(value[0]))
             newStr.append(',' + str(value[1]))
             newStr.append(',' + str(value[2]))
                
             csv_writer.writerow(newStr)
         csvfile.close()
     
     print 'finish'        
