#![feature(phase)]
extern crate serialize;
extern crate regex;
#[phase(plugin)] extern crate regex_macros;
extern crate hyper;

use std::os;
use std::io::{TcpListener, TcpStream};
use std::io::{Acceptor, Listener};
use std::io::BufferedReader;
use influxdata::{GraphitePoint, InfluxDataRecord, InfluxDatabase};

mod influxdata;

// Convert each stream of Carbon lines to Influx data records, and post them
fn handle_connection(stream: TcpStream, db: &InfluxDatabase) {
  let mut file = BufferedReader::new(stream);
  // This is not efficient.  We should really collect all the records which
  // belong to a metric together, though one Graphite stream is typically from
  // one host and will not send metrics multiple times.
  let records: Vec<InfluxDataRecord> =
    file.lines().filter_map(|line| {
      GraphitePoint::from_carbon_line(line.unwrap().as_slice())
    }).map(|point| {
      let mut record = InfluxDataRecord::new_graphite(point.metric_name);
      record.push_graphite_point(point.timestamp, point.metric_value);
      record
    }).collect();

  println!("Sending a batch of {} records...", records.len());
  match db.send_records(&records) {
    // TODO: better error handling, retries, etc.... for now error is just logged
    Err(e)    =>  println!("Send ERROR: {}", e),
    Ok(resp)  =>  println!("Success: got back {}", resp.status)
  }
}

/**
 * Loop and listen for connections, accepting them and having a handler handle each stream
 */
fn listen_loop(port: int, db: &InfluxDatabase) {
  let listener = TcpListener::bind("127.0.0.1", port as u16);
  let mut acceptor = listener.listen();

  for stream in acceptor.incoming() {
      match stream {
          Err(e)     => { println!("ERROR {} listening to port", e); break }
          Ok(stream) => handle_connection(stream, db)
      }
  }

  // close the socket server
  drop(acceptor);
}

fn main() {
  let args = os::args();
  if args.len() != 5 {
    println!("Syntax: {} <src-port> <influx-host> <influx-port> <influx-dbname>", args[0]);
    return;
  }

  let source_port: int = from_str(args[1].as_slice().trim()).unwrap();
  let influx_host = args[2].clone();
  let influx_port: int = from_str(args[3].as_slice().trim()).unwrap();
  let influx_dbname = args[4].clone();

  println!("Relaying Carbon source port {} to InfluxDB at {}:{}, database {}",
           source_port, influx_host, influx_port, influx_dbname);

  let db = InfluxDatabase { host: influx_host,
                            port: influx_port,
                            database: influx_dbname,
                            username: "test".to_string(),
                            password: "test".to_string()
                          };

  listen_loop(source_port, &db);
}
