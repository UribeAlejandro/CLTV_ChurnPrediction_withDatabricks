# Databricks notebook source
# MAGIC %md
# MAGIC # Setup
# MAGIC This notebooks installs required libraries and downloads raw data

# COMMAND ----------

# MAGIC %pip install -r requirements.txt
# MAGIC dbutils.library.restartPython()

# COMMAND ----------

# MAGIC %md
# MAGIC - Drop any old copies of data
# MAGIC - Ensure destination folder exists

# COMMAND ----------

path_data = "/dbfs/tmp/clv/online_retail"

# COMMAND ----------

# MAGIC %sh 
# MAGIC rm -rf {path_data}
# MAGIC mkdir -p {path_data}

# COMMAND ----------

dbutils.jobs.taskValues.set(key = 'path_data', value = path_data)
