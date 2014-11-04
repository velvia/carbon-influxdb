// A module containing data structures and functions to send data to Influx,
// with a particular leaning on Graphite compatibility

use serialize::json;
use hyper::client::request::Request;
use hyper::client::response::Response;
use hyper::{Url, HttpResult, HttpIoError};
use hyper::header::common::content_type::ContentType;
use hyper::mime::Mime;

#[deriving(Show)]
pub struct GraphitePoint {
  pub metric_name: String,
  pub metric_value: f32,
  pub timestamp: u64       // Unix Epoch - seconds since 1/1/1970
}

impl GraphitePoint {
  //
  // Parses a line of Graphite carbon input into a GraphitePoint.  The line has the format:
  // <metricname> <metricvalue> <timestamp-epoch>
  //
  pub fn from_carbon_line(line: &str) -> Option<GraphitePoint> {
    let re = regex!(r"[ \t]+");
    let fields: Vec<&str> = re.split(line.trim()).collect();
    if fields.len() != 3 { return None; }
    let (value, timestamp) = (from_str::<f32>(fields[1]), from_str::<u64>(fields[2]));
    match (value, timestamp) {
      (Some(val), Some(time)) => Some(GraphitePoint {
                                   metric_name: fields[0].to_string(),
                                   metric_value: val,
                                   timestamp: time
                                 }),
      _                       => None
    }
  }
}

#[deriving(Encodable)]
#[deriving(Show)]
pub struct InfluxDataRecord {
  pub series_name: String,
  // Not sure this is the right choice here, but to replicate a heap allocated String for
  // every data record seems dumb.  Advice recommended.
  pub columns: Vec<&'static str>,
  points: Vec<json::Json>
}

impl InfluxDataRecord {
  // Create a new `InfluxDataRecord` for standard Graphite data points
  //
  // Columns: time and value
  pub fn new_graphite(name: String) -> InfluxDataRecord {
    InfluxDataRecord { series_name: name,
                       columns: vec!["time", "value"],
                       points: Vec::new() }
  }

  // Add a single graphite point to the record
  pub fn push_graphite_point(&mut self, timestamp: u64, value: f32) {
    self.points.push(json::List(vec![json::U64(timestamp), json::F64(value as f64)]))
  }
}

// Represents a single InfluxDB database to push to
pub struct InfluxDatabase {
  pub host: String,
  pub port: int,
  pub database: String,
  pub username: String,
  pub password: String
}

impl InfluxDatabase {
  // Sends a bunch of `InfluxDataRecord`s to the given Influx databse
  pub fn send_records(&self, records: &Vec<InfluxDataRecord>) -> HttpResult<Response> {
    let data: String = json::encode(records);
    let www_form_type: Mime = from_str("application/x-www-form-urlencoded").unwrap();
    Request::post(self.get_post_url())
           .and_then(|req| {
             let mut mut_req = req;
             mut_req.headers_mut().set(ContentType(www_form_type.clone()));
             mut_req.start()
           })
           .and_then(|stream_req| {
             let mut writer = stream_req;
             let write_res = writer.write_line(data.as_slice());
             if write_res.is_err() { return Err(HttpIoError(write_res.err().unwrap())) }
             writer.send()
           })
  }

  fn get_post_url(&self) -> Url {
    let url_str = format!("http://{}:{}/db/{}/series?u={}&p={}",
                          self.host, self.port, self.database, self.username, self.password);
    Url::parse(url_str.as_slice()).unwrap()
  }
}