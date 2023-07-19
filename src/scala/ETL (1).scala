// Databricks notebook source
// DBTITLE 1,Import required libraries


// COMMAND ----------

import java.nio.file.{FileSystems, Files}
import scala.collection.JavaConverters._

// COMMAND ----------

// DBTITLE 1,Get environment variables
// MAGIC %python
// MAGIC output_directory = dbutils.jobs.taskValues.get(taskKey = "Setup_Env", key = "path_data")

// COMMAND ----------

// MAGIC %md ##Step 1: Access the Data
// MAGIC
// MAGIC The dataset we will use for this exercise is the [Online Retail Data Set](http://archive.ics.uci.edu/ml/datasets/Online+Retail) available from the UCI Machine Learning Repository:

// COMMAND ----------

// DBTITLE 1,Download Data Set
val output_directory = "/dbfs/tmp/clv/online_retail/"
val url = "http://archive.ics.uci.edu/ml/machine-learning-databases/00352/Online%20Retail.xlsx"

// xlsx_filename = wget.download(url, out=output_directory)
// print(xlsx_filename)

// COMMAND ----------

val dir = FileSystems.getDefault.getPath(output_directory)

// COMMAND ----------

var file_path = ""
val filesSeq = Files.list(dir).iterator().asScala.filter(_.getFileName.toString().endsWith(".xlsx")).toSeq
// val filesSeqString = filesSeq.foreach(_.toAbsolutePath().toString())
// if (filesSeq.isEmpty) {
//   throw new RuntimeException("No file was found")
// } else {
//   file_path = filesSeq(0)
// }

// COMMAND ----------

val file_path = filesSeq(0).toString()

// COMMAND ----------

// MAGIC %md The dataset is made available as an Excel spreadsheet.  We can read this data to a pandas dataframe as follows:

// COMMAND ----------

import org.apache.spark.sql.types.{IntegerType,StringType,StructType,StructField,DateType, DoubleType}

val simpleSchema = StructType(Array(
    StructField("InvoiceNo",StringType,true),
    StructField("StockCode",StringType,true),
    StructField("Description",StringType,true),
    StructField("Quantity", IntegerType, true),
    StructField("InvoiceDate", DateType, true),
    StructField("UnitPrice", DoubleType, true),
    StructField("CustomerID", StringType, true),
    StructField("Country", StringType, true)
  ))

// COMMAND ----------

// MAGIC %sh
// MAGIC cd ..
// MAGIC pwd

// COMMAND ----------

val df = spark.read.format("com.crealytics.spark.excel")
.option("header", true)
.option("inferSchema", "true")
.load("~/dbfs/tmp/clv/online_retail/Online%20Retail (1).xlsx")

// COMMAND ----------

filesSeq(0)

// COMMAND ----------



// COMMAND ----------



// COMMAND ----------



// COMMAND ----------



// COMMAND ----------



// COMMAND ----------

// DBTITLE 1,Read Data
// schema of the excel spreadsheet data range
orders_schema = {
  'InvoiceNo':str,
  'StockCode':str,
  'Description':str,
  'Quantity':np.int64,
  'InvoiceDate':np.datetime64,
  'UnitPrice':np.float64,
  'CustomerID':str,
  'Country':str  
  }

# read spreadsheet to pandas dataframe
# the xlrd library must be installed for this step to work 
orders_pd = pd.read_excel(
  xlsx_filename, 
  sheet_name='Online Retail',
  header=0, # first row is header
  dtype=orders_schema
  )

# calculate sales amount as quantity * unit price
orders_pd['SalesAmount'] = orders_pd['Quantity'] * orders_pd['UnitPrice']

# display first few rows from the dataset
orders_pd.head(10)

// COMMAND ----------

// MAGIC %md The data in the workbook are organized as a range in the Online Retail spreadsheet.  Each record represents a line item in a sales transaction. The fields included in the dataset are:
// MAGIC
// MAGIC | Field | Description |
// MAGIC |-------------:|-----:|
// MAGIC |InvoiceNo|A 6-digit integral number uniquely assigned to each transaction|
// MAGIC |StockCode|A 5-digit integral number uniquely assigned to each distinct product|
// MAGIC |Description|The product (item) name|
// MAGIC |Quantity|The quantities of each product (item) per transaction|
// MAGIC |InvoiceDate|The invoice date and a time in mm/dd/yy hh:mm format|
// MAGIC |UnitPrice|The per-unit product price in pound sterling (£)|
// MAGIC |CustomerID| A 5-digit integral number uniquely assigned to each customer|
// MAGIC |Country|The name of the country where each customer resides|
// MAGIC |SalesAmount| Derived as Quantity * UnitPrice |
// MAGIC
// MAGIC Of these fields, the ones of particular interest for our work are InvoiceNo which identifies the transaction, InvoiceDate which identifies the date of that transaction, and CustomerID which uniquely identifies the customer across multiple transactions. The SalesAmount field is derived from the Quantity and UnitPrice fields in order to provide as a monetary amount around which we can estimate value.

// COMMAND ----------

// MAGIC %md ##Step 2: Explore the Dataset
// MAGIC
// MAGIC In a real-world scenario, our customer data will often be much larger than what could fit into a pandas dataframe, let alone an Excel spreadsheet.  These data will typically be loaded into our lakehouse environment and made accessible as a queriable table which we can interact with using the distributed resources of our Spark environment.  To simulate this, we'll go ahead and convert our pandas dataframe to a Spark dataframe and then to a queriable though temporary view:

// COMMAND ----------

// DBTITLE 1,Convert Dataframe to View
// MAGIC %python
// MAGIC # convert pandas DF to Spark DF
// MAGIC orders = spark.createDataFrame(orders_pd)
// MAGIC
// MAGIC # present Spark DF as queryable view
// MAGIC orders.createOrReplaceTempView('orders') 

// COMMAND ----------

// MAGIC %md To get started, let's take a look at the typical purchase frequency pattern and daily spend of a customer. We will group this at a daily level so that multiple purchases occuring on the same day will be treated as a single purchase event.  (This is a typical pattern employed in most CLV estimations.)

// COMMAND ----------

// DBTITLE 1,Examine Daily Transactions
// MAGIC %sql -- unique transactions and daily sales by date
// MAGIC
// MAGIC SELECT 
// MAGIC   CustomerID,
// MAGIC   TO_DATE(InvoiceDate) as InvoiceDate,
// MAGIC   COUNT(DISTINCT InvoiceNo) as Transactions,
// MAGIC   SUM(SalesAmount) as SalesAmount
// MAGIC FROM orders
// MAGIC GROUP BY CustomerID, TO_DATE(InvoiceDate)
// MAGIC ORDER BY CustomerID, InvoiceDate;

// COMMAND ----------

// MAGIC %md The range of daily spend is quite wide with a few customers purchasing over £70,000 in a single day.  Without much knowledge of the underlying business, it's hard to say if this is level of spending is consistent with the expectations of the site. Still, it seems pretty clear that those are anomalous transactions and should be removed from our calculations.
// MAGIC
// MAGIC There are also transactions with NULL Customer ID which most likely indicates data quality issue.  We will remove these data now:

// COMMAND ----------

// DBTITLE 1,Cleanse Dataset
// MAGIC %python
// MAGIC # identify outlier customers
// MAGIC customers_to_exclude = (
// MAGIC   orders
// MAGIC     .groupBy('customerid','invoicedate')
// MAGIC       .agg(fn.sum('salesamount').alias('salesamount'))
// MAGIC     .filter('salesamount=70000')
// MAGIC     .select('customerid')
// MAGIC     .distinct()
// MAGIC   )
// MAGIC
// MAGIC # remove bad records and outlier customers
// MAGIC cleansed_orders = (
// MAGIC   orders
// MAGIC     .filter('customerid is not null')
// MAGIC     .join(
// MAGIC       customers_to_exclude,
// MAGIC       on='customerid',
// MAGIC       how='leftanti'
// MAGIC     )
// MAGIC   )
// MAGIC
// MAGIC # reload orders pandas dataframe from cleansed data
// MAGIC orders_pd = cleansed_orders.toPandas()
// MAGIC
// MAGIC # make cleansed data accessible for queries
// MAGIC _ = cleansed_orders.createOrReplaceTempView('orders')
// MAGIC display(spark.table('orders'))

// COMMAND ----------

// MAGIC %md Examining the daily transaction activity in our dataset, we can see the first transaction occurs December 1, 2010 and the last is on December 9, 2011 making this a dataset that's a little more than 1 year in duration. The daily transaction count shows there is quite a bit of volatility in daily activity for this online retailer. We can smooth this out a bit by summarizing activity by month. It's important to keep in mind that December 2011 only has 9 days worth of data which will make that 

// COMMAND ----------

// DBTITLE 1,Examine Transactions by Month
// MAGIC %sql -- unique transactions by month
// MAGIC
// MAGIC SELECT 
// MAGIC   TRUNC(InvoiceDate, 'month') as InvoiceMonth,
// MAGIC   COUNT(DISTINCT InvoiceNo) as Transactions,
// MAGIC   SUM(SalesAmount) as SalesAmount
// MAGIC FROM orders
// MAGIC GROUP BY TRUNC(InvoiceDate, 'month') 
// MAGIC ORDER BY InvoiceMonth;

// COMMAND ----------

// MAGIC %md For the little more than 1-year period for which we have data, we see over four-thousand unique customers (excluding customers with NULL IDs).  These customers generated about twenty-two thousand unique transactions amounting to a total of 8 million pounds:

// COMMAND ----------

// DBTITLE 1,Examine Summary Metrics
// MAGIC %sql -- unique customers and transactions
// MAGIC
// MAGIC SELECT
// MAGIC  COUNT(DISTINCT CustomerID) as Customers,
// MAGIC  COUNT(DISTINCT InvoiceNo) as Transactions,
// MAGIC  SUM(SalesAmount) as SalesAmount
// MAGIC FROM orders
// MAGIC WHERE CustomerID IS NOT NULL and InvoiceDate<'2011-12-01';

// COMMAND ----------

// MAGIC %md A little quick math may lead us to estimate that, on average, each customer is responsible for about 5 transactions with a total of 2000 pounds, but this would not provide an accurate representation of customer activity. For a better understanding let's look at the distribution for transaction frequency and customer spend respectively.  We will combine transactions that occur on the same date to be consistent with how the BTYD models typically explore these kinds of data:

// COMMAND ----------

// DBTITLE 1,Examine Distribution of Per-Customer Purchase Date Counts
// MAGIC %sql -- the distribution of per-customer transaction counts
// MAGIC      -- with consideration of same-day transactions as a single transaction 
// MAGIC
// MAGIC SELECT
// MAGIC   x.Transactions,
// MAGIC   COUNT(x.*) as Occurrences
// MAGIC FROM (
// MAGIC   SELECT
// MAGIC     CustomerID,
// MAGIC     COUNT(DISTINCT TO_DATE(InvoiceDate)) as Transactions
// MAGIC   FROM orders
// MAGIC   GROUP BY CustomerID
// MAGIC   ) x
// MAGIC GROUP BY 
// MAGIC   x.Transactions
// MAGIC ORDER BY
// MAGIC   x.Transactions;

// COMMAND ----------

// MAGIC %md What we can see in this data is that frequency tends to what we might describe as a negative binomial distribution where there is rapidly declining frequency values as we move from left to right along the x-axis.
// MAGIC
// MAGIC Focusing on customers with repeat purchases, we can examine the distribution of the days between purchase events. What's important to note here is that most customers return to the site within 2 to 3 months of a prior purchase.  Longer gaps do occur but significantly fewer customers have longer gaps between returns.  This is important to understand in the context of our BYTD models in that the time since we last saw a customer is a critical factor to determining whether they will ever come back with the probability of return dropping as more and more time passes since a customer's last purchase event:

// COMMAND ----------

// DBTITLE 1,Examine Avg Number Days between Purchase Dates
// MAGIC %sql -- distribution of per-customer average number of days between purchase events
// MAGIC
// MAGIC WITH CustomerPurchaseDates
// MAGIC   AS (
// MAGIC     SELECT DISTINCT
// MAGIC       CustomerID,
// MAGIC       TO_DATE(InvoiceDate) as InvoiceDate
// MAGIC     FROM orders 
// MAGIC     )
// MAGIC SELECT -- Per-Customer Average Days Between Purchase Events
// MAGIC   AVG(
// MAGIC     DATEDIFF(a.NextInvoiceDate, a.InvoiceDate)
// MAGIC     ) as AvgDaysBetween
// MAGIC FROM ( -- Purchase Event and Next Purchase Event by Customer
// MAGIC   SELECT 
// MAGIC     x.CustomerID,
// MAGIC     x.InvoiceDate,
// MAGIC     MIN(y.InvoiceDate) as NextInvoiceDate
// MAGIC   FROM CustomerPurchaseDates x
// MAGIC   INNER JOIN CustomerPurchaseDates y
// MAGIC     ON x.CustomerID=y.CustomerID AND x.InvoiceDate < y.InvoiceDate
// MAGIC   GROUP BY 
// MAGIC     x.CustomerID,
// MAGIC     x.InvoiceDate
// MAGIC     ) a
// MAGIC GROUP BY CustomerID

// COMMAND ----------

// MAGIC %md Let's look at the distirbution of the spend amounts we are seeing in these data:

// COMMAND ----------

// DBTITLE 1,Examine Sales Distribution
// MAGIC %sql -- daily sales by customer (for daily sales between 0 and 2500£)
// MAGIC
// MAGIC SELECT
// MAGIC   CustomerID,
// MAGIC   TO_DATE(InvoiceDate) as InvoiceDate,
// MAGIC   SUM(SalesAmount) as SalesAmount
// MAGIC FROM orders
// MAGIC GROUP BY CustomerID, TO_DATE(InvoiceDate)
// MAGIC HAVING SalesAmount BETWEEN 0 AND 2500

// COMMAND ----------

// MAGIC %md The distribution of daily spend in this narrowed range is centered around 200 to 400 pound sterling with a long-tail towards higher ranges of spend. It's clear this is not a normal (gaussian) distribution.
// MAGIC
// MAGIC This awareness of how spend and frequency both adhere to distributions that rapidly decline from left to right is important to understanding how the BTYD models think about the data inputs we'll provide them.  More on that later. 

// COMMAND ----------

// MAGIC %md ##Step 3: Calculate Customer Metrics
// MAGIC
// MAGIC The dataset with which we are working consists of raw transactional history.  To apply the BTYD models, we need to derive several per-customer metrics:</p>
// MAGIC
// MAGIC * **Frequency** - the number of dates on which a customer made a purchase subsequent to the date of the customer's first purchase
// MAGIC * **Age (Term)** - the number of time units, *e.g.* days, since the date of a customer's first purchase to the current date (or last date in the dataset)
// MAGIC * **Recency** - the age of the customer (as previously defined) at the time of their last purchase
// MAGIC * **Monetary Value** - the average per transaction-date spend by a customer during repeat purchases.  (Margin and other monetary values may also be used if available.)
// MAGIC
// MAGIC It's important to note that when calculating metrics such as customer age that we need to consider when our dataset terminates.  Calculating these metrics relative to today's date can lead to erroneous results.  Given this, we will identify the last date in the dataset and define that as *today's date* for all calculations.
// MAGIC
// MAGIC To get started with these calculations, let's take a look at how they are performed using the built-in functionality of the [btyd](https://btyd.readthedocs.io/en/latest/User%20Guide.html) library:

// COMMAND ----------

// DBTITLE 1,Use the BTYD Library to Calculate Metrics
// MAGIC %python
// MAGIC # set the last transaction date as the end point for this historical dataset
// MAGIC current_date = orders_pd['InvoiceDate'].max()
// MAGIC
// MAGIC # calculate the required customer metrics
// MAGIC metrics_pd = (
// MAGIC   btyd.utils.summary_data_from_transaction_data(
// MAGIC     orders_pd,
// MAGIC     customer_id_col='CustomerID',
// MAGIC     datetime_col='InvoiceDate',
// MAGIC     observation_period_end = current_date, 
// MAGIC     freq='D',
// MAGIC     monetary_value_col='SalesAmount'  # use sales amount to determine monetary value
// MAGIC     )
// MAGIC   )
// MAGIC
// MAGIC # display first few rows
// MAGIC metrics_pd.head(10)

// COMMAND ----------

// MAGIC %md The btyd library, like many Python libraries, is single-threaded.  Using this library to derive customer metrics on larger transactional datasets may overwhelm your system or simply take too long to complete. For this reason, let's examine how these metrics can be calculated using the distributed capabilities of Apache Spark.
// MAGIC
// MAGIC In the following cells we are going to use Programmatic Spark SQL API which may align better with some Data Scientist's preferences for complex data manipulation. Of course, you can derive the same results with Spark SQL using a SQL statement. In the code in the next cell, we first assemble each customer's order history consisting of the customer's ID, the date of their first purchase (first_at), the date on which a purchase was observed (transaction_at) and the current date (using the last date in the dataset for this value).  From this history, we can count the number of repeat transaction dates (frequency), the days between the last and first transaction dates (recency), the days between the current date and first transaction (T) and the associated monetary value (monetary_value) on a per-customer basis:

// COMMAND ----------

// DBTITLE 1,Use PySpark SQL API to Calculate Metrics
// MAGIC %python
// MAGIC # programmatic sql api calls to derive summary customer stats
// MAGIC # valid customer orders
// MAGIC x = (
// MAGIC     orders
// MAGIC       .withColumn('transaction_at', fn.to_date('invoicedate'))
// MAGIC       .groupBy('customerid', 'transaction_at')
// MAGIC       .agg(fn.sum('salesamount').alias('salesamount'))   # SALES AMOUNT
// MAGIC     )
// MAGIC
// MAGIC # calculate last date in dataset
// MAGIC y = (
// MAGIC   orders
// MAGIC     .groupBy()
// MAGIC     .agg(fn.max(fn.to_date('invoicedate')).alias('current_dt'))
// MAGIC   )
// MAGIC
// MAGIC # calculate first transaction date by customer
// MAGIC z = (
// MAGIC   orders
// MAGIC     .groupBy('customerid')
// MAGIC     .agg(fn.min(fn.to_date('invoicedate')).alias('first_at'))
// MAGIC   )
// MAGIC
// MAGIC # combine customer history with date info 
// MAGIC a = (x
// MAGIC     .crossJoin(y)
// MAGIC     .join(z, on='customerid', how='inner')
// MAGIC     .selectExpr(
// MAGIC       'customerid', 
// MAGIC       'first_at', 
// MAGIC       'transaction_at',
// MAGIC       'salesamount',
// MAGIC       'current_dt'
// MAGIC       )
// MAGIC     )
// MAGIC
// MAGIC # calculate relevant metrics by customer
// MAGIC metrics_api = (a
// MAGIC            .groupBy(a.customerid, a.current_dt, a.first_at)
// MAGIC            .agg(
// MAGIC              (
// MAGIC               fn.countDistinct(a.transaction_at)-1).cast(FloatType()).alias('frequency'),
// MAGIC               fn.datediff(fn.max(a.transaction_at), a.first_at).cast(FloatType()).alias('recency'),
// MAGIC               fn.datediff(a.current_dt, a.first_at).cast(FloatType()).alias('T'),
// MAGIC               fn.when(fn.countDistinct(a.transaction_at)==1,0)                           # MONETARY VALUE
// MAGIC                 .otherwise(
// MAGIC                   fn.sum(
// MAGIC                     fn.when(a.first_at==a.transaction_at,0)
// MAGIC                       .otherwise(a.salesamount)
// MAGIC                     )/(fn.countDistinct(a.transaction_at)-1)
// MAGIC                  ).alias('monetary_value')
// MAGIC                )
// MAGIC            .select('customerid','frequency','recency','T','monetary_value')
// MAGIC            .orderBy('customerid')
// MAGIC           )
// MAGIC
// MAGIC display(metrics_api)

// COMMAND ----------

// MAGIC %md Let's take a moment to compare the data in these different metrics datasets, just to confirm the results are identical.  Instead of doing this record by record, let's calculate summary statistics across each dataset to verify their consistency:
// MAGIC
// MAGIC NOTE You may notice means and standard deviations vary slightly in the hundred-thousandths and millionths decimal places.  This is a result of slight differences in data types between the pandas and Spark dataframes but do not affect our results in a meaningful way. 

// COMMAND ----------

// DBTITLE 1,Summary Metrics for Library-derived Values
// MAGIC %python
// MAGIC # summary data from btyd
// MAGIC metrics_pd.describe()

// COMMAND ----------

// DBTITLE 1,Summary Metrics for Spark-derived Values
// MAGIC %python
// MAGIC # summary data from pyspark.sql API
// MAGIC metrics_api.toPandas().describe()

// COMMAND ----------

// MAGIC %md The metrics we've calculated represent summaries of a whole time series of data.  To support model validation and avoid overfitting, a common pattern with time series data is to train models on an earlier portion of the time series (known as the *calibration* period) and validate against a later portion of the time series (known as the *holdout* period). In the btyd library, the derivation of per customer metrics using calibration and holdout periods is done through a simple method call.  Because our dataset consists of a limited range for data, we will instruct this library method to use the last 90-days of data as the holdout period.  A simple parameter called a widget on the Databricks platform has been implemented to make the configuration of this setting easily changeable:

// COMMAND ----------

// DBTITLE 1,Define Holdout Days
// MAGIC %python
// MAGIC holdout_days = 90

// COMMAND ----------

// DBTITLE 1,Use the BTYD Library to Calculate Metrics with Holdout
// MAGIC %python
// MAGIC # set the last transaction date as the end point for this historical dataset
// MAGIC current_date = orders_pd['InvoiceDate'].max()
// MAGIC
// MAGIC # define end of calibration period
// MAGIC calibration_end_date = current_date - timedelta(days = holdout_days)
// MAGIC
// MAGIC # calculate the required customer metrics
// MAGIC metrics_cal_pd = (
// MAGIC   btyd.utils.calibration_and_holdout_data(
// MAGIC     orders_pd,
// MAGIC     customer_id_col='CustomerID',
// MAGIC     datetime_col='InvoiceDate',
// MAGIC     observation_period_end = current_date,
// MAGIC     calibration_period_end=calibration_end_date,
// MAGIC     freq='D',
// MAGIC     monetary_value_col='SalesAmount'  # use sales amount to determine monetary value
// MAGIC     )
// MAGIC   )
// MAGIC
// MAGIC # display first few rows
// MAGIC metrics_cal_pd.head(10)

// COMMAND ----------

// MAGIC %md As before, we are going to use the programmatic SQL API to derive this same information:

// COMMAND ----------

// DBTITLE 1,Use PySpark SQL API to Calculate Metrics with Holdout
// MAGIC %python
// MAGIC # valid customer orders
// MAGIC x = (
// MAGIC   orders
// MAGIC     .withColumn('transaction_at', fn.to_date('invoicedate'))
// MAGIC     .groupBy('customerid', 'transaction_at')
// MAGIC     .agg(fn.sum('salesamount').alias('salesamount'))
// MAGIC   )
// MAGIC
// MAGIC # calculate last date in dataset
// MAGIC y = (
// MAGIC   orders
// MAGIC     .groupBy()
// MAGIC     .agg(fn.max(fn.to_date('invoicedate')).alias('current_dt'))
// MAGIC   )
// MAGIC
// MAGIC # calculate first transaction date by customer
// MAGIC z = (
// MAGIC   orders
// MAGIC     .groupBy('customerid')
// MAGIC     .agg(fn.min(fn.to_date('invoicedate')).alias('first_at'))
// MAGIC   )
// MAGIC
// MAGIC # combine customer history with date info (CUSTOMER HISTORY)
// MAGIC p = (x
// MAGIC     .crossJoin(y)
// MAGIC     .join(z, on='customerid', how='inner')
// MAGIC     .withColumn('duration_holdout', fn.lit(holdout_days))
// MAGIC     .select(
// MAGIC       'customerid',
// MAGIC       'first_at',
// MAGIC       'transaction_at',
// MAGIC       'current_dt',
// MAGIC       'salesamount',
// MAGIC       'duration_holdout'
// MAGIC       )
// MAGIC      .distinct()
// MAGIC     ) 
// MAGIC
// MAGIC # calculate relevant metrics by customer
// MAGIC # note: date_sub requires a single integer value unless employed within an expr() call
// MAGIC a = (p
// MAGIC        .where(p.transaction_at < fn.expr('date_sub(current_dt, duration_holdout)')) 
// MAGIC        .groupBy(p.customerid, p.current_dt, p.duration_holdout, p.first_at)
// MAGIC        .agg(
// MAGIC          (fn.countDistinct(p.transaction_at)-1).cast(FloatType()).alias('frequency_cal'),
// MAGIC          fn.datediff( fn.max(p.transaction_at), p.first_at).cast(FloatType()).alias('recency_cal'),
// MAGIC          fn.datediff( fn.expr('date_sub(current_dt, duration_holdout)'), p.first_at).cast(FloatType()).alias('T_cal'),
// MAGIC          fn.when(fn.countDistinct(p.transaction_at)==1,0)
// MAGIC            .otherwise(
// MAGIC              fn.sum(
// MAGIC                fn.when(p.first_at==p.transaction_at,0)
// MAGIC                  .otherwise(p.salesamount)
// MAGIC                )/(fn.countDistinct(p.transaction_at)-1)
// MAGIC              ).alias('monetary_value_cal')
// MAGIC        )
// MAGIC     )
// MAGIC
// MAGIC b = (p
// MAGIC       .where((p.transaction_at >= fn.expr('date_sub(current_dt, duration_holdout)')) & (p.transaction_at <= p.current_dt) )
// MAGIC       .groupBy(p.customerid)
// MAGIC       .agg(
// MAGIC         fn.countDistinct(p.transaction_at).cast(FloatType()).alias('frequency_holdout'),
// MAGIC         fn.avg(p.salesamount).alias('monetary_value_holdout')
// MAGIC         )
// MAGIC    )
// MAGIC
// MAGIC metrics_cal_api = (
// MAGIC                  a
// MAGIC                  .join(b, on='customerid', how='left')
// MAGIC                  .select(
// MAGIC                    'customerid',
// MAGIC                    'frequency_cal',
// MAGIC                    'recency_cal',
// MAGIC                    'T_cal',
// MAGIC                    'monetary_value_cal',
// MAGIC                    fn.coalesce(b.frequency_holdout, fn.lit(0.0)).alias('frequency_holdout'),
// MAGIC                    fn.coalesce(b.monetary_value_holdout, fn.lit(0.0)).alias('monetary_value_holdout'),
// MAGIC                    'duration_holdout'
// MAGIC                    )
// MAGIC                  .orderBy('customerid')
// MAGIC               )
// MAGIC
// MAGIC display(metrics_cal_api)

// COMMAND ----------

// MAGIC %md Using summary stats, we can again verify these different units of logic are returning the same results:

// COMMAND ----------

// DBTITLE 1,Summary Metrics for Library-derived Values
// MAGIC %python
// MAGIC # summary data from btyd
// MAGIC metrics_cal_pd.describe()

// COMMAND ----------

// DBTITLE 1,Summary Metrics for Spark-derived Values
// MAGIC %python
// MAGIC # summary data from pyspark.sql API
// MAGIC metrics_cal_api.toPandas().describe()

// COMMAND ----------

// MAGIC %md Carefully examine the monetary holdout value (monetary_value_holdout) calculated with the btyd library.  You should notice the values produced are significantly lower than those arrived at by the Spark code.  This is because the btyd library is averaging the individual line items on a given transaction date instead of averaging the transaction date total.  A change request has been submitted with the caretakers of the btyd library, but we believe the average of transaction date totals is the correct value and will use that for the remainder of this notebook.

// COMMAND ----------

// MAGIC %md Our data prep is nearly done.  The last thing we need to do is exclude customers for which we have no repeat purchases, *i.e.* frequency or frequency_cal is 0. The Pareto/NBD and BG/NBD models we will use focus exclusively on performing calculations on customers with repeat transactions.  A modified BG/NBD model, *i.e.* MBG/NBD, which allows for customers with no repeat transactions is supported by the btyd library.  However, to stick with the two most popular of the BYTD models in use today, we will limit our data to align with their requirements:
// MAGIC
// MAGIC NOTE We are showing how both the pandas and Spark dataframes are filtered simply to be consistent with side-by-side comparisons earlier in this section of the notebook.  In a real-world implementation, you would simply choose to work with pandas or Spark dataframes for data preparation.

// COMMAND ----------

// DBTITLE 1,Remove Customers with No Repeat Purchases
// MAGIC %python
// MAGIC # remove customers with no repeats (complete dataset)
// MAGIC filtered_pd = metrics_pd[metrics_pd['frequency'] > 0]
// MAGIC filtered = metrics_api.where(metrics_api.frequency > 0)
// MAGIC
// MAGIC ## remove customers with no repeats in calibration period
// MAGIC filtered_cal_pd = metrics_cal_pd[metrics_cal_pd['frequency_cal'] > 0]
// MAGIC filtered_cal = metrics_cal_api.where(metrics_cal_api.frequency_cal > 0)

// COMMAND ----------

// MAGIC %md Finally, we need to consider what to do about the negative daily totals found in our dataset.  Without any contextual information about the retailer from which this dataset is derived, we might assume these negative values represent returns.  Ideally, we'd match returns to their original purchases and adjust the monetary values for the original transaction date.  That said, we do not have the information required to consistently do this and so we will simply include the negative return values in our daily transaction totals. Where this causes a daily total to be £0 or lower, we will simply exclude that value from our analysis.  Outside of a demonstration setting, this would typically be inappropriate, but then again, you'd probably have access to the data required to properly reconcile these values:

// COMMAND ----------

// DBTITLE 1,Remove Problematic Records
// MAGIC %python
// MAGIC # exclude dates with negative totals (see note above) 
// MAGIC filtered = filtered.where(filtered.monetary_value > 0)
// MAGIC filtered_cal = filtered_cal.where(filtered_cal.monetary_value_cal > 0)

// COMMAND ----------

// MAGIC %md ##Step 4: Train the Customer Engagement Probability Model
// MAGIC
// MAGIC In customer lifetime value calculations, we are often projecting far into the future to determine the return we might expect from a given customer or household. Inherent in these projections is an assumption that the customer will remain engaged until that point in time.  By recognizing that customer retention degrades over time, we can estimate where in a declining distribution a given customer resides and estimate a probability that the customer will stick around until the period into which we are projecting.  This logic is captured in what is known as the Beta-Geometric/Negative Binomial Distribution or BetaGeo model.  (You can read the details about this model [here](http://brucehardie.com/papers/018/fader_et_al_mksc_05.pdf).)
// MAGIC
// MAGIC Using the btyd library, we can setup such a model using either the fitters or models API.  We will use the fitters API as it appears to provide more robust functionality during evaluation and deployment:

// COMMAND ----------

// DBTITLE 1,Train BetaGeo Model
// MAGIC %python
// MAGIC # load spark dataframe to pandas dataframe
// MAGIC input_pd = filtered_cal.toPandas()
// MAGIC #grouping and resetting index to help the model converge during training
// MAGIC bg_training_data = input_pd.groupby(["frequency_cal", "recency_cal", "T_cal"]).size().reset_index()
// MAGIC
// MAGIC # fit a model
// MAGIC bgf_engagement = BetaGeoFitter(penalizer_coef=1.0)
// MAGIC bgf_engagement.fit( bg_training_data['frequency_cal'], bg_training_data['recency_cal'], bg_training_data['T_cal'])
// MAGIC

// COMMAND ----------

// MAGIC %md With our model now fit, let's make some predictions for the holdout period. We use the *conditional_expected_number_of_purchases_up_to_time* method to make this prediction.  We'll grab the actuals for that same period to enable comparison in a subsequent step:

// COMMAND ----------

// DBTITLE 1,Estimate Purchases in Holdout Period
// MAGIC %python
// MAGIC # score the model
// MAGIC # get predicted frequency during holdout period
// MAGIC frequency_holdout_actual = input_pd['frequency_holdout']
// MAGIC # get actual frequency during holdout period
// MAGIC frequency_holdout_predicted = bgf_engagement.conditional_expected_number_of_purchases_up_to_time(input_pd['duration_holdout'], input_pd['frequency_cal'], input_pd['recency_cal'], input_pd['T_cal'])
// MAGIC

// COMMAND ----------

// MAGIC %md
// MAGIC
// MAGIC  With actual and predicted values in hand, we can calculate some standard evaluation metrics.  Let's wrap those calculations in a function call to make evaluation easier in future steps:
// MAGIC  we can calculate the RMSE for our newly trained model:

// COMMAND ----------

// DBTITLE 1,Evaluate Model Error
// MAGIC %python
// MAGIC # define function to enable different evaluation metrics
// MAGIC def score_model(actuals, predicted, metric='mse'):
// MAGIC   metric = metric.lower() # make sure metric name is lower case
// MAGIC   
// MAGIC   # Mean Squared Error and Root Mean Squared Error
// MAGIC   if metric=='mse' or metric=='rmse':
// MAGIC     val = np.sum(np.square(actuals-predicted))/actuals.shape[0]
// MAGIC     if metric=='rmse':
// MAGIC         val = np.sqrt(val)
// MAGIC   elif metric=='mae': # Mean Absolute Error
// MAGIC     val = np.sum(np.abs(actuals-predicted))/actuals.shape[0]
// MAGIC   else:
// MAGIC     val = None
// MAGIC   
// MAGIC   return val
// MAGIC
// MAGIC # calculate mse for predictions relative to holdout
// MAGIC mse = score_model(frequency_holdout_actual, frequency_holdout_predicted, 'rmse')
// MAGIC print('RMSE: {0}'.format(mse))

// COMMAND ----------

// MAGIC %md While important for comparing models, the RMSE metric is a bit more challenging to interpret in terms of the overall goodness of fit of any individual model.  To provide more insight into how well our model fits our data, let's visualize the relationships between some actual and predicted values.
// MAGIC
// MAGIC To get started, we can examine how purchase frequencies in the calibration period relates to actual (frequency_holdout) and predicted (model_predictions) frequencies in the holdout period:

// COMMAND ----------

// DBTITLE 1,Visualize Predicted vs. Actual Purchases in Holdout Period
// MAGIC %python
// MAGIC
// MAGIC plot_calibration_purchases_vs_holdout_purchases(
// MAGIC   bgf_engagement, 
// MAGIC   input_pd, 
// MAGIC   n=90, 
// MAGIC   **{'figsize':(8,8)}
// MAGIC   )
// MAGIC   
// MAGIC display()

// COMMAND ----------

// MAGIC %md What we see here is that a higher frequency of purchase predicts in the calibration period predicts a higher frequency of purchases in the holdout period.  For customers with lower frequencies, the correlation is pretty reliable.  For customers with higher frequencies, the model tends to be a bit more conservative and underestimates purchases in that period.  Some of that may have to do with the fact that a 90-day holdout ending on Dec 9 would be intersecting with the traditional holiday shopping period within which most consumers make a higher than normal number of purchases.  Ideally, we'd employ multiple years of data and find a holdout period that's a little more middle-of-the-road than something in that specific period.
// MAGIC
// MAGIC Examining time since purchase and purchase frequencies, we can see a much tighter correlation in the data whereby longer times since last purchase in the calibration period tends to predict lower purchase frequencies in the holdout period:

// COMMAND ----------

// DBTITLE 1,Visualize Purchase Frequency vs. Actual Purchases in Holdout Period
// MAGIC %python
// MAGIC plot_calibration_purchases_vs_holdout_purchases(
// MAGIC   bgf_engagement, 
// MAGIC   input_pd, 
// MAGIC   kind='time_since_last_purchase', 
// MAGIC   n=90, 
// MAGIC   **{'figsize':(8,8)}
// MAGIC   )
// MAGIC
// MAGIC display()

// COMMAND ----------

// MAGIC %md Plugging the age of the customer at the time of the last purchase into the chart shows that the timing of the last purchase in a customer's lifecycle doesn't seem to have a strong influence on the number of purchases in the holdout period until a customer becomes quite old.  This would indicate that the customers that stick around a long while are likely to be more frequently engaged:
// MAGIC
// MAGIC **NOTE** As a reminder, *age* is also known as *term* and refers to the number of periods (*days*) since a customer's first purchase.

// COMMAND ----------

// DBTITLE 1,Visualize Customer Age (Term) vs. Purchases in Holdout Period
// MAGIC %python
// MAGIC plot_calibration_purchases_vs_holdout_purchases(
// MAGIC   bgf_engagement, 
// MAGIC   input_pd, 
// MAGIC   kind='recency_cal', 
// MAGIC   n=300,
// MAGIC   **{'figsize':(8,8)}
// MAGIC   )
// MAGIC
// MAGIC display()

// COMMAND ----------

// MAGIC %md From a quick visual inspection, it's fair to say our model isn't perfect but there are some useful patterns that it captures. Using these patterns, we might calculate the probability a customer remains engaged:

// COMMAND ----------

// DBTITLE 1,Estimate Probability Customer is Retained
// MAGIC %python
// MAGIC # add a field with the probability a customer is currently "alive"
// MAGIC filtered_pd['prob_alive']=bgf_engagement.conditional_probability_alive(
// MAGIC     filtered_pd['frequency'], 
// MAGIC     filtered_pd['recency'], 
// MAGIC     filtered_pd['T']
// MAGIC     )
// MAGIC
// MAGIC filtered_pd.head(10)

// COMMAND ----------

// MAGIC
// MAGIC %md
// MAGIC The model derives whether customer will staying engaged/alive and derives probabilities of the customers re-engaging by looking at the history of a individual customer transactions. 
// MAGIC
// MAGIC The exact math of how these probabilities are derived is tricky but by plotting the probability of being alive as a heatmap relative to frequency and recency, we can understand the probabilities assigned to the intersections of these two values:

// COMMAND ----------

// DBTITLE 1,Visualize Relationship Between Recency, Frequency & Probability of Retention
// MAGIC %python
// MAGIC # set figure size
// MAGIC plt.subplots(figsize=(12, 8))
// MAGIC
// MAGIC plot_probability_alive_matrix(bgf_engagement) 
// MAGIC
// MAGIC display()

// COMMAND ----------

// MAGIC %md In addition to predicting the probability a customer is still alive, we can calculate the number of purchases expected from a customer over a given future time interval, such as over the next 30-days:

// COMMAND ----------

// DBTITLE 1,Visualize Relationship Between Recency, Frequency & Expected Purchases within a Time Span
// MAGIC %python
// MAGIC # set figure size
// MAGIC plt.subplots(figsize=(12, 8))
// MAGIC
// MAGIC plot_frequency_recency_matrix(bgf_engagement, T=30) 
// MAGIC
// MAGIC display()

// COMMAND ----------

// MAGIC %md As before, we can calculate this probability for each customer based on their current metrics:

// COMMAND ----------

// DBTITLE 1,Estimate Number of Purchases within a Time Span
// MAGIC %python
// MAGIC filtered_pd['purchases_next30days']=(
// MAGIC   bgf_engagement.conditional_expected_number_of_purchases_up_to_time(
// MAGIC     30, 
// MAGIC     filtered_pd['frequency'], 
// MAGIC     filtered_pd['recency'], 
// MAGIC     filtered_pd['T']
// MAGIC     )
// MAGIC   )
// MAGIC
// MAGIC filtered_pd.head(10)

// COMMAND ----------

// MAGIC %md 
// MAGIC There are numerous ways we might make use of the trained BTYD model. 
// MAGIC * We may wish to understand the probability a customer is still engaged.  
// MAGIC * We may also wish to predict the number of purchases expected from the customer over some number of days. 
// MAGIC
// MAGIC All we need to make these predictions is our trained model and values of frequency, recency and age (T) for the customer.

// COMMAND ----------

// MAGIC %md ##Step 5: Train the Customer Spend Model
// MAGIC
// MAGIC The BetaGeo model provides us with the ability to predict a customer's retention into a future period.  The [GammaGamma model](http://www.brucehardie.com/notes/025/gamma_gamma.pdf) (named for the two gamma distributions is estimates) is used to estimate the monetary value of the spend in those periods.  A key assumption of the GammaGamma model is that a customer's purchase frequency does not affect the monetary value of their spend.  We can test this quickly with simply correlation calculation:

// COMMAND ----------

// DBTITLE 1,Examine Correlation Between Frequency & Monetary Value
// MAGIC %python
// MAGIC filtered.corr('frequency', 'monetary_value')

// COMMAND ----------

// MAGIC %md We can now fit our GammaGamma model:

// COMMAND ----------

// DBTITLE 1,Train GammaGamma Model
// MAGIC %python
// MAGIC # instantiate and configure model
// MAGIC ggm_spend = GammaGammaFitter(penalizer_coef=0.002)
// MAGIC
// MAGIC ggm_training_data = filtered_cal.toPandas()
// MAGIC print(ggm_training_data.columns)
// MAGIC # fit the model
// MAGIC ggm_spend.fit(ggm_training_data['frequency_cal'], ggm_training_data['monetary_value_cal'])

// COMMAND ----------

// MAGIC %md The evaluation of the spend model is fairly straightforward.  We might examine how well predicted values align with actuals in the holdout period and derive an RMSE from it:

// COMMAND ----------

// DBTITLE 1,Evaluate the GammaGamma Model
// MAGIC %python
// MAGIC # evaluate the model
// MAGIC monetary_actual = input_pd['monetary_value_holdout']
// MAGIC monetary_predicted = ggm_spend.conditional_expected_average_profit(input_pd['frequency_holdout'], input_pd['monetary_value_holdout'])
// MAGIC mse = score_model(monetary_actual, monetary_predicted, 'rmse')
// MAGIC print('RMSE: {0}'.format(mse))

// COMMAND ----------

// MAGIC %md We might also visually inspect how are predicted spend values align with actuals, a technique employed in the [original paper](http://www.brucehardie.com/notes/025/gamma_gamma.pdf) that described the Gamma-Gamma model:

// COMMAND ----------

// DBTITLE 1,Compare Histograms for Actual & Predicted Spend
// MAGIC %python
// MAGIC
// MAGIC # define histogram bin count
// MAGIC bins = 10
// MAGIC
// MAGIC # plot size
// MAGIC plt.figure(figsize=(15, 5))
// MAGIC
// MAGIC # histogram plot values and presentation
// MAGIC plt.hist(monetary_actual, bins, label='actual', histtype='bar', color='STEELBLUE', rwidth=0.99)
// MAGIC plt.hist( monetary_predicted, bins, label='predict', histtype='step', color='ORANGE',  rwidth=0.99)
// MAGIC
// MAGIC # place legend on chart
// MAGIC plt.legend(loc='upper right')

// COMMAND ----------

// MAGIC %md With only 10 bins, our model looks like it lines up with our actuals data pretty nicely.  If we expand the bin count, we see that the model underpredicts the occurrence of the lowest valued spend while following the remaining structure of the data.  Interestingly, a similar pattern was observed in the original paper cited earlier:

// COMMAND ----------

// DBTITLE 1,Compare Histograms for Actual & Predicted Spend with Higher Bin Count
// MAGIC %python
// MAGIC # define histogram bin count
// MAGIC bins = 40
// MAGIC
// MAGIC # plot size
// MAGIC plt.figure(figsize=(15, 5))
// MAGIC
// MAGIC # histogram plot values and presentation
// MAGIC plt.hist(monetary_actual, bins, label='actual', histtype='bar', color='STEELBLUE', rwidth=0.99)
// MAGIC plt.hist( monetary_predicted, bins, label='predict', histtype='step', color='ORANGE',  rwidth=0.99)
// MAGIC
// MAGIC # place legend on chart
// MAGIC plt.legend(loc='upper right')

// COMMAND ----------

// MAGIC %md ##Step 6: Calculate Customer Lifetime Value
// MAGIC
// MAGIC Using our two models, we can now estimate the probability a customer will be retained into a future period as well the amount they are likely to spend in that period, providing us the basis for a customer lifetime value estimation. The btyd library provides some built-in functionality for this that simplifies the calculation.
// MAGIC
// MAGIC Please note that we are estimating CLV for only a 12-month period given the limited data inputs available to us.  We are also using a monthly discount rate of 1%.  If you only have access to an annual discount rate, be sure to convert it to monthly using [this formula](https://www.experiglot.com/2006/06/07/how-to-convert-from-an-annual-rate-to-an-effective-periodic-rate-javascript-calculator/).

// COMMAND ----------

// DBTITLE 1,Calculate 12-Month CLV
// MAGIC %python
// MAGIC clv_input_pd = filtered.toPandas()
// MAGIC
// MAGIC # calculate the 1-year CLV for each customer
// MAGIC clv_input_pd['clv'] = (
// MAGIC   ggm_spend.customer_lifetime_value(
// MAGIC     bgf_engagement, #the model to use to predict the number of future transactions
// MAGIC     clv_input_pd['frequency'],
// MAGIC     clv_input_pd['recency'],
// MAGIC     clv_input_pd['T'],
// MAGIC     clv_input_pd['monetary_value'],
// MAGIC     time=12, # months
// MAGIC     discount_rate=0.01 # monthly discount rate ~ 12.7% annually
// MAGIC   )
// MAGIC )
// MAGIC
// MAGIC clv_input_pd.head(10)

// COMMAND ----------

// MAGIC %md CLV is a powerful metric used by organizations to plan targeted promotional activities and assess customer equity. As such, it would be very helpful if we could convert our models into an easy to use function which we could employ in batch, streaming and interactive scenarios.
// MAGIC
// MAGIC To help us package our models for deployment, we'll save the BetaGeo model to a temporary path.  This is necessary because we will import the saved model as an artifact tied to our GammaGamma model within MLflow.  It doesn't really matter which we save so long as we remember which is the primary model and which must be retrieved from the model artifacts when we write our custom pyfunc model wrapper (in the next cell):

// COMMAND ----------

// DBTITLE 1,Persist the BetaGeo Model
// MAGIC %python
// MAGIC # location to save temp copy of btyd model
// MAGIC CEP_model_path = '/dbfs/tmp/customer_engagement_model.pkl'
// MAGIC
// MAGIC # delete any prior copies that may exist
// MAGIC try:
// MAGIC   dbutils.fs.rm(CEP_model_path)
// MAGIC except:
// MAGIC   pass
// MAGIC
// MAGIC # save the model to the temp location
// MAGIC bgf_engagement.save_model(CEP_model_path)

// COMMAND ----------

// MAGIC %md Now, let's define the custom wrapper for our spend model. 
// MAGIC The challenge now is to package our  spend model into something we could re-use for this purpose. As a platform, mlflow is designed to solve a wide range of challenges that come with model development and deployment, including the deployment of models as functions and microservice applications. 
// MAGIC
// MAGIC MLFlow tackles deployment challenges out of the box for a number of [popular model types](https://www.mlflow.org/docs/latest/models.html#built-in-model-flavors). However, btyd models are not one of these. To use mlflow as our deployment vehicle, we need to write a custom wrapper class which translates the standard mlflow API calls into logic which can be applied against our model.
// MAGIC
// MAGIC To illustrate this, we've implemented a wrapper class for our btyd model which maps the mlflow *predict()* method to multiple prediction calls against our model. 
// MAGIC  Notice that the *predict()* method is fairly simple and returns just a CLV value.  Notice too that it assumes a consistent value for month and discount rate is provided in the incoming data.
// MAGIC
// MAGIC Besides modification to the *predict()* method logic, a new definition for *load_context()* is provided.  This method is called when an [mlflow](https://mlflow.org/) model is instantiated.  In it, we will load our btyd model artifact:

// COMMAND ----------

// DBTITLE 1,Define a Customer Model Wrapper
// MAGIC %python
// MAGIC # create wrapper for btyd model
// MAGIC class _clvModelWrapper(mlflow.pyfunc.PythonModel):
// MAGIC   
// MAGIC     def __init__(self, ggm_spend):
// MAGIC       self.ggm_spend = ggm_spend
// MAGIC         
// MAGIC     def load_context(self, context):
// MAGIC       # load base model fitter from btyd library
// MAGIC       from btyd.fitters.beta_geo_fitter import BetaGeoFitter
// MAGIC       
// MAGIC       # instantiate btyd
// MAGIC       self.bgf_engagement = BetaGeoFitter()
// MAGIC       
// MAGIC       # load CEP_model from mlflow
// MAGIC       self.bgf_engagement.load_model(context.artifacts['CEP_model'])
// MAGIC       
// MAGIC     def predict(self, context, dataframe):
// MAGIC       
// MAGIC       # access input series
// MAGIC       frequency = dataframe['frequency']
// MAGIC       recency = dataframe['recency']
// MAGIC       T = dataframe['T']
// MAGIC       monetary_value = dataframe['monetary_value']
// MAGIC       months = int(dataframe['months'].iloc[0]) # scaler value
// MAGIC       discount_rate = float(dataframe['discount_rate'].iloc[0]) # scaler value
// MAGIC       
// MAGIC       # make CLV prediction
// MAGIC       results = pd.DataFrame(
// MAGIC           self.ggm_spend.customer_lifetime_value(
// MAGIC             self.bgf_engagement, #the model to use to predict the number of future transactions
// MAGIC             frequency,
// MAGIC             recency,
// MAGIC             T,
// MAGIC             monetary_value,
// MAGIC             time=months,
// MAGIC             discount_rate=discount_rate
// MAGIC             ),
// MAGIC           columns=['clv']
// MAGIC           )
// MAGIC       
// MAGIC       return results[['clv']]

// COMMAND ----------

// MAGIC %md We now need to register our model with mlflow. As we do this, we inform it of the wrapper that maps its expected API to the model's functionality.  We also provide environment information to instruct it as to which libraries it needs to install and load for our model to work:
// MAGIC
// MAGIC NOTE We would typically train and log our model as a single step but in this notebook we've separated the two actions in order to focus here on custom model deployment.  For examine more common patterns of mlflow implementation, please refer to [this](https://docs.databricks.com/applications/mlflow/model-example.html) and other examples available online. 

// COMMAND ----------

// DBTITLE 1,Persist Model with MLflow
// MAGIC %python
// MAGIC # set mlflow experiment explicitly - this allows the notebook to run end-to-end in a Workflow
// MAGIC username = dbutils.notebook.entry_point.getDbutils().notebook().getContext().userName().get()
// MAGIC mlflow.set_experiment('/Users/{}/bytd'.format(username))
// MAGIC
// MAGIC # add btyd to conda environment info
// MAGIC conda_env = mlflow.pyfunc.get_default_conda_env()
// MAGIC
// MAGIC conda_env['dependencies'][2]['pip'] += [f'btyd=={btyd.__version__}'] 
// MAGIC
// MAGIC # save model run to mlflow
// MAGIC with mlflow.start_run(run_name='deployment run') as run:
// MAGIC   
// MAGIC   mlflow.pyfunc.log_model(
// MAGIC     'model', 
// MAGIC     python_model=_clvModelWrapper(ggm_spend), 
// MAGIC     conda_env=conda_env,
// MAGIC     artifacts={'CEP_model': CEP_model_path} # path where to locate the saved version of the BetaGeo model
// MAGIC     )  

// COMMAND ----------

// MAGIC %md Now that our model along with its dependency information and class wrapper have been recorded, let's use mlflow to convert the model into a function we can employ against a Spark dataframe:

// COMMAND ----------

// DBTITLE 1,Instantiate Persisted Model as Spark UDF
// MAGIC %python
// MAGIC # define function based on mlflow recorded model
// MAGIC clv_udf = mlflow.pyfunc.spark_udf(
// MAGIC     spark, 
// MAGIC     'runs:/{0}/model'.format(run.info.run_id), 
// MAGIC     result_type=DoubleType()
// MAGIC     )
// MAGIC
// MAGIC   # register the function for use in SQL
// MAGIC _ = spark.udf.register('clv', clv_udf)

// COMMAND ----------

// MAGIC %md Our model is now available for use with the Programmatic SQL API:
// MAGIC
// MAGIC **NOTE** We are passing values into the udf as a struct so that each column is named.  We will use these names to extract data from the incoming dataset.

// COMMAND ----------

// DBTITLE 1,Estimate CLV
// MAGIC %python
// MAGIC # create a temp view for SQL demonstration (next cell)
// MAGIC filtered.createOrReplaceTempView('customer_metrics')
// MAGIC
// MAGIC # demonstrate function call on Spark DataFrame
// MAGIC display(
// MAGIC   filtered
// MAGIC     .withColumn('inputs', fn.struct('frequency', 'recency', 'T', 'monetary_value', fn.lit('12').alias('months'), fn.lit(0.01).alias('discount_rate')))
// MAGIC     .withColumn('clv', clv_udf('inputs'))
// MAGIC     .selectExpr(
// MAGIC       'customerid', 
// MAGIC       'inputs',
// MAGIC       'clv'
// MAGIC       )
// MAGIC     )

// COMMAND ----------

// MAGIC %md © 2023 Databricks, Inc. All rights reserved. The source in this notebook is provided subject to the Databricks License. All included or referenced third party libraries are subject to the licenses set forth below.
// MAGIC
// MAGIC | library                                | description             | license    | source                                              |
// MAGIC |----------------------------------------|-------------------------|------------|-----------------------------------------------------|
// MAGIC | btyd | Successor to the Lifetimes library for implementing Buy Till You Die and Customer Lifetime Value statistical models in Python | Apache 2.0  | https://pypi.org/project/btyd/   |
// MAGIC | openpyxl | Python library to read/write Excel 2010 xlsx/xlsm/xltx/xltm files| MIT | https://pypi.org/project/openpyxl/ |
