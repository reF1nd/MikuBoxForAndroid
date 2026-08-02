package libcore

import (
	"bufio"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"github.com/oschwald/maxminddb-golang"
	"github.com/sagernet/sing-box/common/srs"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
)

type stagedRuleSet struct {
	temporaryPath string
	finalPath     string
}

func parseRuleSetCodes(rawCodes string) ([]string, error) {
	seen := make(map[string]bool)
	for _, rawCode := range strings.Fields(rawCodes) {
		code := strings.ToLower(strings.TrimSpace(rawCode))
		if code == "" || seen[code] {
			continue
		}
		for _, character := range code {
			if character < 'a' || character > 'z' {
				if character < '0' || character > '9' {
					if character != '-' && character != '_' {
						return nil, fmt.Errorf("invalid rule-set code: %s", rawCode)
					}
				}
			}
		}
		seen[code] = true
	}
	codes := make([]string, 0, len(seen))
	for code := range seen {
		codes = append(codes, code)
	}
	sort.Strings(codes)
	return codes, nil
}

func writeRuleSetBatch(outputDirectory, prefix string, rules map[string][]option.HeadlessRule) (err error) {
	if err = os.MkdirAll(outputDirectory, 0o700); err != nil {
		return err
	}
	staged := make([]stagedRuleSet, 0, len(rules))
	defer func() {
		for _, item := range staged {
			_ = os.Remove(item.temporaryPath)
		}
	}()

	codes := make([]string, 0, len(rules))
	for code := range rules {
		codes = append(codes, code)
	}
	sort.Strings(codes)
	for _, code := range codes {
		ruleList := rules[code]
		if len(ruleList) == 0 {
			return fmt.Errorf("empty rule-set: %s-%s", prefix, code)
		}
		temporaryFile, createErr := os.CreateTemp(outputDirectory, "."+prefix+"-"+code+"-*.tmp")
		if createErr != nil {
			return createErr
		}
		temporaryPath := temporaryFile.Name()
		staged = append(staged, stagedRuleSet{
			temporaryPath: temporaryPath,
			finalPath:     filepath.Join(outputDirectory, prefix+"-"+code+".srs"),
		})
		if err = srs.Write(temporaryFile, option.PlainRuleSet{Rules: ruleList}, C.RuleSetVersion2); err != nil {
			temporaryFile.Close()
			return fmt.Errorf("write %s-%s: %w", prefix, code, err)
		}
		if err = temporaryFile.Close(); err != nil {
			return err
		}
		verifyFile, openErr := os.Open(temporaryPath)
		if openErr != nil {
			return openErr
		}
		_, err = srs.Read(bufio.NewReader(verifyFile), false)
		verifyFile.Close()
		if err != nil {
			return fmt.Errorf("verify %s-%s: %w", prefix, code, err)
		}
	}

	for _, item := range staged {
		if err = os.Rename(item.temporaryPath, item.finalPath); err != nil {
			return err
		}
	}
	return nil
}

// ConvertGeoIPRuleSets converts all requested country codes in one MaxMind DB scan.
func ConvertGeoIPRuleSets(databasePath, outputDirectory, rawCodes string) error {
	codes, err := parseRuleSetCodes(rawCodes)
	if err != nil || len(codes) == 0 {
		return err
	}
	reader, err := openGeoIP(databasePath)
	if err != nil {
		return err
	}
	defer reader.Close()

	requested := make(map[string]bool, len(codes))
	addressRules := make(map[string][]string, len(codes))
	for _, code := range codes {
		requested[code] = true
	}
	networks := reader.reader.Networks(maxminddb.SkipAliasedNetworks)
	for networks.Next() {
		var countryCode string
		ipNetwork, countryErr := networks.Network(&countryCode)
		if countryErr != nil {
			return fmt.Errorf("read GeoIP network: %w", countryErr)
		}
		countryCode = strings.ToLower(countryCode)
		if requested[countryCode] {
			addressRules[countryCode] = append(addressRules[countryCode], ipNetwork.String())
		}
	}
	if err = networks.Err(); err != nil {
		return err
	}

	rules := make(map[string][]option.HeadlessRule, len(codes))
	for _, code := range codes {
		addresses := addressRules[code]
		if len(addresses) == 0 {
			return fmt.Errorf("no networks found for country code: %s", code)
		}
		rules[code] = []option.HeadlessRule{{
			Type: C.RuleTypeDefault,
			DefaultOptions: option.DefaultHeadlessRule{
				IPCIDR: addresses,
			},
		}}
	}
	return writeRuleSetBatch(outputDirectory, "geoip", rules)
}

// ConvertGeositeRuleSets converts all requested categories after opening the DB once.
func ConvertGeositeRuleSets(databasePath, outputDirectory, rawCodes string) error {
	codes, err := parseRuleSetCodes(rawCodes)
	if err != nil || len(codes) == 0 {
		return err
	}
	reader, err := openGeoSite(databasePath)
	if err != nil {
		return err
	}
	rules := make(map[string][]option.HeadlessRule, len(codes))
	for _, code := range codes {
		rules[code], err = reader.Rules(code)
		if err != nil {
			return err
		}
	}
	return writeRuleSetBatch(outputDirectory, "geosite", rules)
}
