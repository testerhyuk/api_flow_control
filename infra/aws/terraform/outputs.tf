output "load_public_ip" {
  description = "부하·관측 서버 공인 IP"
  value       = aws_instance.server["load"].public_ip
}

output "grafana_url" {
  description = "Grafana 주소"
  value       = "http://${aws_instance.server["load"].public_ip}:3000"
}

output "server_public_ips" {
  description = "서버별 공인 IP"
  value       = { for name, server in aws_instance.server : name => server.public_ip }
}
