locals {
  c7i = "c7i-flex.large"
  m7i = "m7i-flex.large"

  servers = merge(
    {
      kafka-1 = { profile = "kafka", type = local.c7i, ip = "10.20.1.11", disk_gb = 40, kafka_node_id = 1 }
      kafka-2 = { profile = "kafka", type = local.c7i, ip = "10.20.1.12", disk_gb = 40, kafka_node_id = 2 }
      kafka-3 = { profile = "kafka", type = local.c7i, ip = "10.20.1.13", disk_gb = 40, kafka_node_id = 3 }
      store   = { profile = "store", type = local.c7i, ip = "10.20.1.20", disk_gb = 20, kafka_node_id = 0 }
      mock    = { profile = "mock", type = local.c7i, ip = "10.20.1.30", disk_gb = 20, kafka_node_id = 0 }
      app-1   = { profile = "app", type = local.c7i, ip = "10.20.1.40", disk_gb = 20, kafka_node_id = 0 }
      load    = { profile = "load", type = local.m7i, ip = "10.20.1.50", disk_gb = 30, kafka_node_id = 0 }
    },
    var.second_app ? {
      app-2 = { profile = "app", type = local.c7i, ip = "10.20.1.41", disk_gb = 20, kafka_node_id = 0 }
    } : {}
  )

  app_ips = [for server in local.servers : server.ip if server.profile == "app"]

  prometheus_config = templatefile("${path.module}/prometheus.yml.tftpl", {
    app_ips = local.app_ips
    mock_ip = local.servers.mock.ip
  })
}

data "http" "my_ip" {
  url = "https://checkip.amazonaws.com"
}

data "aws_ami" "ubuntu" {
  most_recent = true
  owners      = ["099720109477"]

  filter {
    name   = "name"
    values = ["ubuntu/images/hvm-ssd-gp3/ubuntu-noble-24.04-amd64-server-*"]
  }
}

resource "aws_vpc" "experiment" {
  cidr_block           = "10.20.0.0/16"
  enable_dns_hostnames = true

  tags = { Name = "flow-control-experiment" }
}

resource "aws_internet_gateway" "experiment" {
  vpc_id = aws_vpc.experiment.id
}

resource "aws_subnet" "experiment" {
  vpc_id                  = aws_vpc.experiment.id
  cidr_block              = "10.20.1.0/24"
  availability_zone       = var.availability_zone
  map_public_ip_on_launch = true

  tags = { Name = "flow-control-experiment" }
}

resource "aws_route_table" "experiment" {
  vpc_id = aws_vpc.experiment.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.experiment.id
  }
}

resource "aws_route_table_association" "experiment" {
  subnet_id      = aws_subnet.experiment.id
  route_table_id = aws_route_table.experiment.id
}

resource "aws_security_group" "experiment" {
  name   = "flow-control-experiment"
  vpc_id = aws_vpc.experiment.id

  ingress {
    description = "experiment servers talk to each other"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    self        = true
  }

  ingress {
    description = "SSH from my IP"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = ["${trimspace(data.http.my_ip.response_body)}/32"]
  }

  ingress {
    description = "Grafana from my IP"
    from_port   = 3000
    to_port     = 3000
    protocol    = "tcp"
    cidr_blocks = ["${trimspace(data.http.my_ip.response_body)}/32"]
  }

  ingress {
    description = "Prometheus from my IP"
    from_port   = 9090
    to_port     = 9090
    protocol    = "tcp"
    cidr_blocks = ["${trimspace(data.http.my_ip.response_body)}/32"]
  }

  egress {
    description = "pull images and packages"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_key_pair" "experiment" {
  key_name   = "flow-control-experiment"
  public_key = file(pathexpand(var.ssh_public_key_path))
}

resource "aws_instance" "server" {
  for_each = local.servers

  ami                    = data.aws_ami.ubuntu.id
  instance_type          = each.value.type
  subnet_id              = aws_subnet.experiment.id
  private_ip             = each.value.ip
  key_name               = aws_key_pair.experiment.key_name
  vpc_security_group_ids = [aws_security_group.experiment.id]

  # Git 이 Windows 에서 줄바꿈을 CRLF 로 바꿔도 서버의 셸 스크립트가 깨지지 않게 한다.
  user_data = replace(templatefile("${path.module}/bootstrap.sh.tftpl", {
    profile           = each.value.profile
    private_ip        = each.value.ip
    kafka_node_id     = each.value.kafka_node_id
    kafka_1_ip        = local.servers.kafka-1.ip
    kafka_2_ip        = local.servers.kafka-2.ip
    kafka_3_ip        = local.servers.kafka-3.ip
    store_ip          = local.servers.store.ip
    mock_ip           = local.servers.mock.ip
    app_ip            = local.servers.app-1.ip
    repository        = var.repository
    image_repository  = var.image_repository
    image_tag         = var.image_tag
    prometheus_config = base64encode(replace(local.prometheus_config, "\r\n", "\n"))
  }), "\r\n", "\n")

  root_block_device {
    volume_type = "gp3"
    volume_size = each.value.disk_gb
  }

  tags = { Name = "flow-control-${each.key}" }

  depends_on = [aws_route_table_association.experiment, aws_internet_gateway.experiment]
}
